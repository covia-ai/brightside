# Security and vault recovery

The security properties of Brightside as implemented, for reviewers,
operators and anyone testing whether an installation can be recovered. It is
not a claim that the host computer, the Java process, the model provider or
user-installed skills are trusted in all circumstances.

- **One secret.** A 32-byte Ed25519 seed is both the venue's signing identity
  and, through a domain-separated hash, the key that opens the store.
- **The recovery invariant.** Recovery phrase plus the retained store equals
  the same identity and the same conversations, memory, skills and secrets.
  The phrase reproduces the seed; the passphrase does not.
- **Nothing sensitive is plaintext at rest**, except a passphrase the owner
  explicitly chose to remember.
- **Loopback only, authenticated only.** The venue binds to `127.0.0.1` with
  anonymous access off; the only outbound traffic is the model call the owner
  asked for.
- **The UI is a separate principal.** The chat acts as the owner's user, and
  Covia's capability checks are the boundary between principals.

The implementation is `Vault`, `Mnemonic` and the recovery flow in
`BrightSide`; the person-facing flow is [ONBOARDING.md](ONBOARDING.md); the
files are listed in [CONFIGURATION.md](CONFIGURATION.md).

## The recovery invariant

The seed has two independent uses: it creates the venue's Ed25519 key pair,
its `did:key` and its signing authority, including the access tokens the venue
trusts; and it deterministically derives the master key that opens
`venue.etch`. The BIP39 recovery phrase reproduces that seed. Therefore:

> **Recovery phrase + retained `venue.etch` = the same venue identity and the
> same lattice state.**

The phrase cannot recreate data if the store has been lost, and a copy of the
store cannot be decrypted without the seed. The salt is not needed when the
phrase is available: Brightside creates a new salt and wraps the recovered seed
under a new passphrase. The salt is needed to unlock the existing identity
envelope with the old passphrase.

## Key hierarchy

```text
new 12-word phrase (128 bits of entropy + BIP39 checksum)
                  │
                  ├─ BIP39 PBKDF2-HMAC-SHA-512/2048, empty BIP39 passphrase
                  ▼
             64-byte BIP39 seed
                  │
                  ├─ SLIP-0010 Ed25519 master derivation, empty path
                  ▼
          PRIMARY ED25519 SEED (32 bytes)
             │                         │
             │                         └─ SHA-256("brightside-etch-v1" || seed)
             │                                            │
             │                                  32-byte Etch master key
             │                                            │
             │                         Etch HKDF-SHA-256 + per-file salt
             │                              ┌──────────────┴──────────────┐
             ▼                              ▼                             ▼
      Ed25519 key pair              ChaCha20 file key          HMAC-SHA-256 header key
             │                              │                             │
      venue DID and signatures       encrypted data records      authenticated v3 header

login passphrase + vault.salt
                  │
                  └─ Argon2id ──► 32-byte passphrase key
                                      ├─ AES-256-GCM ──► identity.enc (primary seed)
                                      └─ AES-256-GCM ──► keys.enc (onboarding key staging)
```

New installations generate twelve words; import and recovery also accept a
valid twenty-four-word phrase. The BIP39 passphrase is empty, so there is no
"25th word". The recovery phrase is a bearer-equivalent master secret and
belongs offline. The raw seed can be revealed under *Settings → Identity*
behind the vault passphrase; it reproduces the identity and the store key but
cannot be turned back into the phrase.

## Algorithms and parameters

| Purpose | Construction | Specification |
|---|---|---|
| Recovery words | BIP39 English mnemonic; 12 words by default, from `SecureRandom` | [BIP-0039](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) |
| Phrase to BIP39 seed | PBKDF2-HMAC-SHA-512, 2,048 iterations, salt `"mnemonic"`, 64-byte output | [BIP-0039](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) |
| BIP39 seed to primary seed | SLIP-0010 Ed25519 master derivation, empty path, left 32 bytes | [SLIP-0010](https://github.com/satoshilabs/slips/blob/master/slip-0010.md) |
| Venue identity and signatures | Ed25519 from the primary seed | [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032) |
| Venue access tokens | JWT/JWS signed with Ed25519 (`EdDSA`); `iss`, `sub`, `aud`, `iat`, `exp` | [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519), [RFC 8037](https://www.rfc-editor.org/rfc/rfc8037), [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725) |
| Passphrase hardening | Argon2id v1.3; 64 MiB, 3 iterations, parallelism 1, 16-byte random salt, 32-byte output | [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106) |
| Seed and key-staging envelopes | AES-256-GCM; fresh 96-bit nonce, 128-bit tag, no associated data; `nonce ‖ ciphertext ‖ tag` | [NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final) |
| Etch master key | `SHA-256(UTF8("brightside-etch-v1") ‖ primarySeed)` | [FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) |
| Etch file subkeys | HKDF-SHA-256 with Etch's per-file salt and separate cipher and MAC contexts | [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869) |
| Etch data confidentiality | ChaCha20 random-access overlay with a 256-bit derived file key | [RFC 8439](https://www.rfc-editor.org/rfc/rfc8439) |
| Etch header authenticity | HMAC-SHA-256 over each of two v3 header copies | [RFC 2104](https://www.rfc-editor.org/rfc/rfc2104) |
| Lattice value identity | Canonical CAD3 encodings and SHA3-256 content identifiers, a Merkle DAG | [CAD003](https://docs.convex.world/docs/cad/encoding) |

Argon2id comes from Bouncy Castle; AES-GCM and `SecureRandom` from the Java
Cryptography Architecture; BIP39, SLIP-0010, Ed25519 and Etch from the
Covia/Convex runtime. Versions are in `pom.xml`. The SHA-256 derivation is
used only with a uniformly random seed and is not a password KDF; Etch then
performs its own domain-separated HKDF derivations.

### Etch confidentiality and integrity boundary

Brightside requests Etch v3 with the ChaCha20 cipher and does not encrypt the
index, so the root radix index and file-layout metadata are plaintext; data
records, including their content-address keys and encodings, are encrypted.
File size and layout are therefore visible to someone who copies the file.

Etch v3 is not a whole-file AEAD container. Its dual-copy header, including
the root hash and clean-close state, is authenticated; ChaCha20 gives the body
confidentiality but not a per-record authentication tag, and CAD3's Merkle
structure is not an AEAD tag over every physical byte. Backups are still
required against corruption, malicious modification and storage failure.

A new file carries a non-secret hint derived from the Etch master key, and a
wrong key fails that check before the header is verified, so a wrong seed
fails before the venue starts. Brightside uses that property to validate a
candidate recovery phrase before replacing any credential.

## Files and their recovery role

The data home is the directory holding `config.json`, by default
`~/.brightside/`. The full listing is in
[CONFIGURATION.md](CONFIGURATION.md); what matters for recovery:

| Path | Protection | Recovery significance |
|---|---|---|
| `venue.etch` | ChaCha20 under the seed-derived key; see the boundary above | **Required** to recover conversations, memory, skills, agents and the secret stores |
| `identity.enc` | the seed, AES-GCM under the passphrase key | With `vault.salt` and the passphrase, recovers the seed. Replaceable from the phrase |
| `vault.salt` | non-secret, never silently replaced | Needed for old-passphrase unlock; not for phrase recovery. Back it up anyway |
| `keys.enc` | transient onboarding key staging, AES-GCM | Normally absent; provider keys live in the stores inside `venue.etch` |
| `unlock.passphrase` | **plaintext**, after explicit opt-in | Anyone who reads it can unlock the vault. Exclude from ordinary backups; cleared on recovery |
| `identity.json` | plaintext name, slug and user DID | **Back it up**: the phrase reproduces the venue, not the user suffix |
| `config.json`, `model.txt`, `prefs.properties` | plaintext, no secrets | Back up when customised |
| `skills/`, `files/` | plaintext, the owner's own | Outside the encrypted store; back up if they matter. Treat third-party skills as executable authority |
| `logs/` | plaintext, rotated | Not needed; may contain operational detail |

Sensitive files are set to mode `0600` where the filesystem supports it; on
Windows they inherit the user's directory ACL. The seed and derived store key
enter the venue configuration in memory just before launch and are never
written to `config.json`; there is no plaintext `venue.key` and no plaintext
legacy mode.

## Recovery procedure

**What to back up.** The recovery phrase, offline and apart from the computer;
and consistent backups of the whole data home, above all `venue.etch` and
`identity.json`. Quit Brightside before copying so the store records a clean
close. Keep the original until the recovered installation is verified.
Recording the non-secret venue DID and public key beside the backup gives an
independent fingerprint to compare afterwards; the identicons on the Identity
page are a quick visual aid, and a reviewer compares the full value.

**Forgotten passphrase.** From the unlock screen, choose *Forgot passphrase?*,
enter the phrase and a new passphrase. Brightside then:

1. normalises and checksum-validates the words;
2. derives the candidate seed through BIP39 and SLIP-0010, and the candidate
   Etch master key;
3. if a store exists and is not open, opens it far enough to check the key
   identity and authenticate the header; if the venue is running, compares the
   candidate with the in-memory seed;
4. only then deletes any staged key file, clears the remembered passphrase and
   writes the recovered seed into a new `identity.enc`;
5. launches the venue with the recovered seed and reopens the store.

Without a store, step 3 has nothing to validate and recovery restores the
identity only. A valid but unrelated phrase is rejected when a store is
present because it cannot authenticate the header. Afterwards, verify the DID
and public key, look at conversations and memory, and confirm the expected
slug is in use. Provider keys reopen with the store; nothing to re-enter.

**Recovery matrix.**

| Material retained | Identity | Lattice data | Provider keys |
|---|---|---|---|
| Whole data home + passphrase | Yes | Yes | Yes |
| `venue.etch` + phrase + `identity.json` | Yes; new envelope and salt are made | Yes | Yes |
| `venue.etch` + raw seed | Yes, with Convex tooling | Yes | Yes |
| Phrase only | Yes; same seed and venue identity | No; an empty store can be created | No |
| Envelope + salt + passphrase, no store | Yes | No | No |
| `venue.etch` alone | No | No | No |

There is no online recovery service, no escrow and no operator backdoor.

## Authentication and network exposure

By default the venue binds HTTP to `127.0.0.1`, disables the anonymous public
principal, requires venue authentication for the API and MCP, and permits
browser Private Network Access preflights so local web tooling can reach the
authenticated loopback API. These are defaults: `config.json` overrides the
venue map, including bind, CORS and authentication. The endpoint is plain
HTTP; binding beyond loopback needs a TLS boundary and restrictive policy, and
a reviewer inspects the effective configuration first.

*Settings → Auth* mints an Ed25519-signed bearer token for the current user or,
as an advanced option, the venue operator: `iss = aud = <venueDID>`, `sub` the
chosen principal. It is shown for copying and never persisted. Instance
takeover uses a five-minute operator token and invokes only the authenticated
shutdown operation. Treat a copied token as a password until it expires.

The desktop chat acts as the named user, not the operator, and Covia's
capability checks remain the boundary between principals. The user has no key
of its own; the venue key signs for it and is shown separately on the Identity
page. `identity.json` pins slug and DID, and launch requires the saved DID to
equal `<runningVenueDID>:u:<slug>`, so a copied or edited profile cannot move
the UI to another principal. The in-process application and the venue share
one trust boundary.

## Runtime secret handling

- Passphrase and temporary seed arrays are cleared on the paths that expose
  them, best-effort. Java strings, UI components, the clipboard, provider SDKs
  and JVM copies cannot be reliably zeroed; use seed export and token copy only
  in a trusted desktop session and clear the clipboard after.
- The seed and derived keys necessarily exist in the running JVM; a debugger,
  a malicious same-user process or a compromised JVM can read them.
- *Log out* clears the user's UI and session state but leaves the venue
  running: a session lock, not key erasure. Quit to release in-memory keys.
- Model calls send the assembled context to the chosen provider under its
  terms; a local model avoids that disclosure.

## Assumptions and limitations

- The phrase compromises both identity and any copied store, because both
  converge on one seed by design. Keep it apart from data backups.
- The minimum passphrase length is eight characters; Argon2id raises the cost
  of guessing but cannot make a weak passphrase strong.
- The remembered passphrase is plaintext by the owner's decision and relies
  only on OS-account and filesystem protection.
- Etch's index is plaintext and its body cipher is not whole-file AEAD.
- Envelope writes are in place, not atomic, and recovery removes the staged
  key file before rewriting the identity envelope, which is why an untouched
  backup is kept until it succeeds; a failed rewrite never invalidates the
  phrase or the store key.
- `identity.json` and a custom store path are recovery metadata the phrase
  does not reproduce.
- Filesystem ACLs, disk encryption, backups, malware protection and physical
  security are the host's responsibility.
- Separating the signing seed from the store key, and transactional rekey, are
  [brightside#5](https://github.com/covia-ai/brightside/issues/5).

## Reviewer verification

`MnemonicTest`, `VaultTest` and `RememberedPassphraseTest` verify
deterministic phrase-to-seed derivation, authenticated failure on a wrong
passphrase, preservation of the salt, rejection of a wrong recovery seed,
reopening the same stored value with the same seed under a new passphrase, and
the plaintext remembered-unlock contract:

```shell
mvn "-Dtest=MnemonicTest,VaultTest,RememberedPassphraseTest" test
```

A release review adds a copy-based drill: create a test installation, record
its DID and public key, write a distinctive marker, quit, copy the data home,
discard the passphrase, recover the copy from the phrase, and compare the
fingerprint and the marker.

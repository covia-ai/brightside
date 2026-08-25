# Brightside — Onboarding & identity design

How a new person goes from launching Brightside to talking to their agent, and
how their **identity**, their **encrypted vault**, and their **model provider**
are set up and later changed. This is a design document; the classes it names are
the intended implementation.

Brightside is a **self-sovereign personal agent**. Onboarding has to make three
things true without ever feeling like a crypto tutorial:

1. The person holds a **cryptographic identity** that is theirs alone (a Convex
   Ed25519 key), with an offline **recovery phrase** (BIP39) they can write down.
2. Everything the agent remembers lives in an **encrypted vault** on their own
   disk, unlocked by a **passphrase** only they know.
3. The agent can **think** — a model provider is chosen and its API key is
   stored, encrypted, in the vault.

Guiding rule (see `DESIGN.md`): the everyday flow is warm and jargon-free; the
crypto is *felt* (it's yours, it's private, you have a recovery phrase), not
explained. Every technical surface stays reachable under **Advanced**.

---

## 1. What lives on disk

Everything is under `~/.brightside/` (the data directory).

| File | Contents | Protection |
|---|---|---|
| `venue.etch` | the lattice store — conversations, memory, agents, **secrets** | **Encrypted** (Etch v3, ChaCha20) with the *vault key* |
| `vault.salt` | 16 random bytes | not secret (Argon2 salt) |
| `identity.enc` | the 32-byte Ed25519 **seed**, encrypted | **Encrypted** (AES-GCM) with the *seed key* |
| `identity.json` | the chosen display name (`u:<name>`) | plaintext, not sensitive |
| `config.json` | theme, venue name/port, chosen **model** | plaintext, **no secrets** |
| `skills/` | filesystem skills (agentskills.io) | plaintext (the user's own) |
| `logs/` | app logs | plaintext |

There is **no plaintext key and no plaintext API key on disk.** The old
plaintext `venue.key` and `config.json → venue.secrets.public.*` are replaced:
the seed is encrypted at rest (`identity.enc`), and API keys live in the venue's
`SecretStore` *inside* the encrypted `venue.etch`.

### The key hierarchy

```
passphrase ──Argon2id(salt)──► 64 bytes ─┬─ [0..32)  vault key  ──► Etch v3 (ChaCha20) master key  → decrypts venue.etch
                                          └─ [32..64) seed key   ──► AES-GCM                         → decrypts identity.enc → Ed25519 seed → venue DID

recovery phrase (BIP39 mnemonic) ──► the same Ed25519 seed   (offline backup; recreates identity.enc if lost)
```

One passphrase unlocks both the store and the identity. The **recovery phrase is
independent of the passphrase** — it reconstructs the identity even if the disk
(and `identity.enc`) is lost. Change the passphrase and only `vault.salt` +
`identity.enc` are rewritten; the identity (DID) and the vault contents are
unchanged.

### Threat model (what this does and doesn't protect)

- **Protects:** data at rest. Someone who copies the disk gets ciphertext only —
  no conversations, no memory, no API keys, no usable identity, without the
  passphrase.
- **Recovery:** the BIP39 phrase restores the *identity*; it does **not** restore
  vault *contents* (those need the passphrase, or are simply lost with the disk).
  This is deliberate and stated plainly to the user.
- **Does not protect:** a compromised running process, keyloggers, or a weak
  passphrase (hence Argon2id, not PBKDF2). Loopback-only venue; the only thing
  that leaves the machine is the model call the user asked for.

---

## 2. Flows

Three entry states, decided at launch by what exists on disk:

- **First run** — no `identity.enc`: run the **first-run wizard** (§3).
- **Returning** — `identity.enc` exists: show the **Unlock** screen (§4) to get
  the passphrase, then start.
- **Running** — later changes go through **Settings** (§5).

The identity and the vault key are needed *before* the venue can launch (the
store can't open without the vault key; the venue needs the seed). So the wizard
and unlock both run **before** `EmbeddedVenue.launch`. The provider/API-key step
runs **after** the venue is up (it writes a secret into the store).

```
launch
  │
  ├─ identity.enc missing ──► First-run wizard ──► derive keys ─┐
  │                                                             │
  └─ identity.enc present ──► Unlock (passphrase) ──► derive ───┤
                                                                ▼
                                          EmbeddedVenue.launch(seed, etch v3 key)
                                                                │
                                        first run? ──► Provider + API key ──► secret:set
                                                                ▼
                                                          Chat (as u:<name>)
```

---

## 3. First-run wizard

A full-window flow reusing the `WelcomePanel` visual language (centred column,
large title, muted subtitle, one primary action). Steps, in order:

### 3.1 Welcome

```
                              ☀️
                    Welcome to Brightside

           Your own agent. On your own machine.
                 Under your own identity.

   Nothing leaves this computer except the model calls you ask for.
   We'll set you up in about a minute.

                        [  Get started  ]
```

### 3.2 Choose a passphrase (the vault)

```
                    Secure your Brightside

   Everything Brightside remembers — your conversations, its memory,
   your keys — is encrypted on this computer with a passphrase only
   you know.

        Passphrase        [ •••••••••••••••••••••••• ]
        Confirm           [ •••••••••••••••••••••••• ]

        ▓▓▓▓▓▓▓▓░░░░  strong

   ⚠  There's no "forgot passphrase". If you lose it, the encrypted
      data is gone — but your recovery phrase (next) still restores
      your identity.

                    [ Back ]      [ Continue ]
```

- Live strength meter (zxcvbn-style heuristic; minimum length enforced).
- Confirm must match. On Continue, generate `vault.salt`, run Argon2id, keep the
  derived keys in memory (never written).

### 3.3 Your identity

```
                     Your identity

   Brightside runs under a key that's yours alone — it's what makes
   this agent *your* agent, not an account on someone's server.

        (•) Create a new identity
        ( ) Import an existing recovery phrase

                    [ Back ]      [ Continue ]
```

**Create → show the recovery phrase:**

```
                  Your recovery phrase

   Write these 12 words down and keep them somewhere safe. They are
   the only way to restore your identity on another computer or if
   this one is lost.

     1. ripple     2. cabin      3. velvet     4. orchard
     5. system     6. quiet      7. marble     8. handle
     9. tunnel    10. bright    11. wander    12. across

              [ Copy ]      [ I've saved it → ]
```

**→ confirm (guards against typos):**

```
                  Confirm your phrase

        Word #4 was …   [ orchard        ]
        Word #9 was …   [ tunnel         ]

                    [ Back ]      [ Continue ]
```

**Import path:**

```
                Import your recovery phrase

   Paste the 12 or 24 words from another Brightside (or any Convex
   BIP39 phrase).

     [ ripple cabin velvet orchard system quiet marble handle   ]
     [ tunnel bright wander across                               ]

     ✓ valid phrase

                    [ Back ]      [ Continue ]
```

- Live validation via `BIP39.checkMnemonic` (green tick / red reason).
- (Advanced, collapsed: "Import a raw 32-byte seed instead".)

On Continue: derive the Ed25519 seed, encrypt it with the *seed key* → write
`identity.enc`. The venue DID is now fixed.

### 3.4 Choose your model

```
                   Choose your assistant's brain

   Brightside thinks using a model you choose. Pick a provider and
   paste your API key — it's stored encrypted, only on this computer.

     Provider   [ Anthropic ▾ ]        Model  [ Claude Sonnet ▾ ]

     API key    [ sk-ant-•••••••••••••••••••••••••••••••••••• ]
                Get a key → console.anthropic.com

     [ Test ]   ✓ reachable

                    [ Back ]      [ Continue ]
```

- Provider dropdown: Anthropic, OpenAI, Grok (xAI), Gemini (Google), DeepSeek,
  Mistral, OpenRouter, Ollama (local, no key).
- Model dropdown repopulates per provider from the venue catalog.
- "Get a key →" links to that provider's console.
- **Test** (optional) does a tiny model call to validate the key.
- No API key handy? A muted "Skip for now — use the offline echo bot" sets
  `v/test/ops/llm` so the app is usable end-to-end without a key.

### 3.5 Your name  ·  3.6 Done

```
        What should I call you?         →      You're all set, Mike.

           [   Mike   ]                     Ask me anything.

           [ Continue ]                        [ Start ]
```

(3.5 is today's `WelcomePanel`; 3.6 hands off to the chat.)

---

## 4. Unlock (returning)

```
                              ☀️
                       Welcome back

        Passphrase   [ •••••••••••••••••••• ]   [ Unlock ]

        Forgot it? Restore from your recovery phrase →
```

- Wrong passphrase is detected cheaply: Etch v3's header carries a keyed HMAC, so
  an incorrect vault key fails header verification without decrypting the store.
  Show "That passphrase didn't work" and let them retry.
- "Restore from your recovery phrase" re-runs 3.2 + 3.3-import + a new passphrase,
  rebuilding `identity.enc`/`vault.salt` — but note it cannot decrypt an old
  `venue.etch` encrypted under a forgotten passphrase (that data is gone).

---

## 5. Settings — model provider & API key

Reachable from **Settings** (a first-class menu, and a gear in the status bar).
Same widget as 3.4, pre-filled with the current provider/model. Non-modal.

```
                        Model & API key

     Provider   [ Anthropic ▾ ]        Model  [ Claude Sonnet ▾ ]

     API key    [ ••••••••••• (set) ]   [ Change ]

     [ Test ]                              [ Save ]
```

- **Model** choice persists to `config.json → chat.llmOperation`
  (`v/models/<provider>/<id>`), applied to the agent on the next message
  (`ChatSession.ensureAgent` re-applies config).
- **API key** is written into the running venue's `SecretStore` via `secret:set`
  under the provider's secret name (`ANTHROPIC_API_KEY`, …), encrypted at rest —
  never to `config.json`. "(set)" shows a key is stored without revealing it.
- Also here (Advanced): **Change passphrase**, **View recovery phrase**
  (passphrase-gated), **Change model default**.

---

## 6. Technical mapping (the exact APIs)

### Passphrase → keys (`Vault`)

- `vault.salt`: 16 random bytes (`SecureRandom`), created once.
- Argon2id via BouncyCastle `Argon2BytesGenerator` + `Argon2Parameters`
  (`ARGON2_id`, memory ~64 MB, iterations ~3, parallelism ~1), output **64
  bytes** → `vaultKey = out[0..32)`, `seedKey = out[32..64)`.
- `vaultKey` is handed to Etch as the master key (Covia uses `etch.key` bytes
  **as-is** — no further derivation).

### Encrypted store (Etch v3)

Injected into the in-memory venue config, never persisted:

```json
{ "store": "<home>/venue.etch",
  "etch": { "version": 3, "cipher": "chacha20", "key": "<vaultKey hex>" } }
```

`covia.venue.Config.getEtchConfig()` reads the `etch` block;
`VenueServer.createStore` calls `EtchStore.create(file, etchConfig)`. Wrong key →
`EtchV3Header` HMAC check fails at open. (Constant: `store: memory` + `etch` is
rejected; keep a file store.)

### Identity (`Mnemonic` + `identity.enc`)

- Generate: `BIP39.createSecureMnemonic(12)` → `BIP39.getSeed(mnemonic,"")`
  (64-byte, PBKDF2-HMAC-SHA512/2048) → `SLIP10.deriveKeyPair(seed64)` →
  `kp.getSeed().toHexString()` = the **32-byte Ed25519 seed** hex.
- Import: `BIP39.checkMnemonic(s)` (null == valid) → same derivation.
- At rest: AES-GCM(seedKey) over the 32-byte seed → `identity.enc` (nonce ‖ ct).
- At launch: decrypt → set venue config **`seed`** (hex) — `VenueServer`
  precedence #1, so the venue uses exactly this key and never auto-generates.
  (We do **not** write the plaintext `venue.key` the venue would otherwise use.)

### Providers (`Providers`)

Static catalog mirroring the venue's `langchain` model catalog:

| Provider | Model op prefix | Secret name | Console |
|---|---|---|---|
| Anthropic | `v/models/anthropic/…` | `ANTHROPIC_API_KEY` | console.anthropic.com |
| OpenAI | `v/models/openai/…` | `OPENAI_API_KEY` | platform.openai.com |
| Grok (xAI) | `v/models/xai/…` | `XAI_API_KEY` | console.x.ai |
| Gemini (Google) | `v/models/gemini/…` | `GOOGLE_API_KEY` | aistudio.google.com |
| DeepSeek | `v/models/deepseek/…` | `DEEPSEEK_API_KEY` | platform.deepseek.com |
| Mistral | `v/models/mistral/…` | `MISTRAL_API_KEY` | console.mistral.ai |
| OpenRouter | `v/models/openrouter/…` | `OPENROUTER_API_KEY` | openrouter.ai |
| Ollama | `v/models/ollama/…` | *(none)* | localhost |

The live model list per provider comes from `langchain:models` over `v/models`;
the static table is the fallback and provides the secret name + console URL.

### API keys (`secret:set`)

Set under the public store so a local `u:<name>` caller resolves it (the venue's
`resolveSecret` falls back to `<venueDID>:public`):

```
v/ops/secret/set  { name: "ANTHROPIC_API_KEY", value: "sk-ant-…", scope: "public" }
```

Encrypted at rest by `SecretStore.deriveKey(keyPair)` inside the already-encrypted
`venue.etch`. The model op resolves it via `LangChainAdapter.resolveApiKey`
(stored secret first, then process env).

---

## 7. Classes to add

- `covia.brightside.vault.Vault` — salt, Argon2id, `vaultKey`/`seedKey`; open/
  create; encrypt/decrypt the seed (`identity.enc`); build the `etch` config
  block; inject `seed` + `etch` into a venue config map.
- `covia.brightside.vault.Mnemonic` — thin BIP39/SLIP10 wrapper: `generate(12)`,
  `validate`, `toSeedHex`, `fromSeedHex` (round-trip helpers).
- `covia.brightside.model.Providers` — the static catalog (name → op prefix,
  secret name, console URL, default model) + `modelOp(provider, id)`.
- `covia.brightside.model.ModelSettings` — apply a provider/model choice to
  `config.json` and set the API key via `secret:set` on a live venue.
- UI: `covia.brightside.ui.onboarding.*` (`OnboardingWizard`, `PassphrasePanel`,
  `IdentityPanel`, `RecoveryPhrasePanel`, `ProviderPanel`, `UnlockPanel`) and
  `covia.brightside.ui.SettingsDialog` (model & key).
- `BrightSide` orchestration: choose wizard/unlock at launch, then
  `EmbeddedVenue.launch(seed, etchConfig, …)`, then provider step on first run.
- `AppConfig`: add `chat.provider`/keep `chat.llmOperation`; drop the plaintext
  `secrets.public` template guidance in favour of the encrypted-store path.

## 8. Migration

An existing install has a plaintext `venue.key` and an unencrypted `venue.etch`.
On first launch after this ships, offer a one-time **"Secure your Brightside"**:
choose a passphrase, derive keys, **rebuild** `venue.etch` as v3-encrypted
(`EtchRebuilder`), encrypt the seed to `identity.enc`, and delete `venue.key`.
Until migrated, Brightside still opens the old unencrypted store (backwards
compatible), so nobody is locked out.
```

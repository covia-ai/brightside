# Onboarding, unlock and recovery

How a new person goes from launching Brightside to talking to their agent, and
how a returning one gets back in. Three things become true on the way, without
it ever feeling like a crypto tutorial:

- **An identity that is theirs alone**: an Ed25519 key, with an offline BIP39
  recovery phrase they can write down.
- **An encrypted vault on their own disk**, unlocked by a passphrase only they
  know. The recovery phrase, not the passphrase, is what reproduces the
  identity and reopens the store.
- **A model to think with**: a provider chosen and its key stored encrypted on
  the venue.

The tone is [DESIGN.md](DESIGN.md)'s: warm and jargon-free, the crypto felt
rather than explained, every technical surface reachable under Settings. What
is on disk and how each file is protected is in
[CONFIGURATION.md](CONFIGURATION.md) and [SECURITY.md](SECURITY.md); the key
hierarchy and the recovery procedure are SECURITY.md's.

## Three entry states

- **First run** — no encrypted identity on disk: the first-run wizard, in the
  main window.
- **Returning** — the identity exists: the unlock dialog on its own, and the
  main window only once the passphrase is accepted and the venue is up.
- **Running** — later changes go through Settings.

The identity seed and the store key are needed before the venue can launch, so
the wizard and unlock both run before it. The code is `OnboardingWizard`,
`UnlockDialog` and `RecoveryDialog` under `ui/onboarding`, `Vault` and
`Mnemonic` under `vault`, with `BrightSide` orchestrating.

## First run

1. **Welcome** — one line of promise (your own agent, on your own machine,
   under your own identity; nothing leaves this computer except the model
   calls you ask for) and one button.
2. **Passphrase** — choose and confirm, with a live strength meter and a
   minimum length, and a warning that the recovery phrase is what rescues a
   forgotten passphrase. The salt is created and the passphrase key derived in
   memory; nothing is written yet.
3. **Identity** — create a new one, or import a recovery phrase. Creating shows
   twelve words to write down and asks for two of them back; importing accepts
   twelve or twenty-four words with live validation, and an advanced fold takes
   a raw seed. The seed is then derived and written encrypted, and the venue's
   DID is fixed.
4. **Model** — provider and model from the venue's catalogue, an API key with
   a link to that provider's console, an optional test call, and a keyless
   "use the offline echo bot" escape so the app works end to end without a
   key. The key is staged encrypted and moved into the venue's secret stores at
   first launch.
5. **Name** — "What should I call you?", then straight into the chat.

## Unlock

A small window of its own, shown before the main window exists: passphrase,
*Unlock*, and *Forgot it? Restore from your recovery phrase*. A wrong
passphrase fails the authenticated decryption and is reported there, not in
the log. While the venue starts, the dialog shows progress and the main window
appears only once it is ready. *Lock* (Settings → General) hides the window and
brings the dialog back. *Remember me* stores the passphrase in plaintext after
explicit opt-in, a trade-off SECURITY.md spells out.

## Recovery from the phrase

*Forgot passphrase?* takes the recovery phrase and a new passphrase. Brightside
verifies the recovered seed against the retained store before touching any
credential file, then rewrites the identity envelope and reopens the existing
store. Conversations, memory, skills and the secret stores, provider keys
included, come back with the store. The phrase alone, without the store,
restores the identity only.

## Settings afterwards

- **Model** — provider, model and key. The model choice persists in
  `config.json` and applies on the next message; the key goes to the venue's
  secret stores at once and never to `config.json`. Stored values are viewable,
  passphrase-gated, under *Settings → Secrets*.
- **Identity** — name, DIDs, public key, the seed behind the passphrase, and
  switching to the operator ([IDENTITY.md](IDENTITY.md)).
- **Advanced** — change passphrase, view the recovery phrase behind the
  passphrase.

Providers and their secret names are catalogued in `model/Providers`; the live
model list comes from the venue.

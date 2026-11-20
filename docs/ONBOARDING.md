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

Eight cards behind five dots: Welcome, Passphrase, Identity (then Recovery
and Confirm, or Import), Name, and Model. Each sits on a card, white over the
sidebar's gold on Light and raised navy on Dark; Enter presses the primary
button. The τ greets on Welcome and again as Finish sets things up, large
and in the brand gradient; every other card carries the small τ.

1. **Welcome**: one line of promise (your own agent, on your own machine,
   under your own identity; nothing leaves this computer except the model
   calls you ask for) and one button.
2. **Passphrase**: choose and confirm, with a live strength meter (a bar and a
   word, in the status tones) and a minimum length. A card with a navy
   outline, never red, warns that the recovery phrase is what rescues a
   forgotten passphrase. The salt is created and the passphrase key derived in
   memory; nothing is written yet.
3. **Identity**: create a new one, or import a recovery phrase. Creating shows
   twelve words in a four-by-three grid, in mono, with Copy, and asks for two
   of them back; importing accepts twelve or twenty-four words with live
   validation.
4. **Name**: "What should I call you?". The identity just made appears under
   the field the moment it exists: its generated mark and short fingerprint,
   in mono, from the `did:key` of the phrase's seed (the DID the venue will
   present).
5. **Model**: provider and model from the venue's catalogue (for OpenRouter
   and Ollama, any model id typed in), an API key with a link to that
   provider's console, and a keyless "use the offline echo bot" escape so the
   app works end to end without a key. The key is staged encrypted and moved
   into the venue's secret stores at first launch. Finish derives the seed,
   writes it encrypted, fixes the venue's DID, and goes straight into the chat.

A test call for the key, and a closing card to connect an agent you already
use, are planned; connecting one today is Agents → Add an agent, or Vault →
Access.

## Unlock

A small window of its own, shown before the main window exists: *Welcome
back*, passphrase, *Open Tau*, and *Forgot passphrase?* (restore from your
recovery phrase). The on-screen copy says "open" rather than "unlock"; the
mechanism, and this document's name for it, is unchanged. A wrong
passphrase fails the authenticated decryption and is reported there, not in
the log. While the venue starts, the dialog shows progress and the main window
appears only once it is ready. *Lock* (Settings → General) hides the window and
brings the dialog back. *Remember me* stores the passphrase in plaintext after
explicit opt-in, a trade-off SECURITY.md spells out. With it stored, Settings →
General → Startup can have a launch unlock with it without asking; Lock still
asks ([LAUNCH.md](LAUNCH.md#startup)).

## Recovery from the phrase

*Forgot passphrase?* takes the recovery phrase and a new passphrase. Brightside
verifies the recovered seed against the retained store before touching any
credential file, then rewrites the identity envelope and reopens the existing
store. Conversations, memory, skills and the secret stores, provider keys
included, come back with the store. The phrase alone, without the store,
restores the identity only.

## Settings afterwards

- **Model** — provider, model and key. The model choice persists in
  Brightside's venue state ([APP_STATE.md](APP_STATE.md)) and applies on the
  next message; the key goes to the venue's
  secret stores at once and never to `config.json`. Stored values are viewable,
  passphrase-gated, under *Settings → Secrets* (a passphrase remembered on
  this computer passes the gate unasked).
- **Identity** — name, DIDs, public key, the seed behind the passphrase, and
  switching to the operator ([IDENTITY.md](IDENTITY.md)).
- **Advanced** — change passphrase, view the recovery phrase behind the
  passphrase.

Providers, their models and their secret names are listed in
`model/Providers`; each listed model is one the venue catalogues. Settings and
the new-agent dialog also offer every model the venue catalogues at
`v/models/<provider>`, and those it reports a provider serving right now, such
as the ones installed in Ollama. For a provider that runs only catalogued
models, a listed model the venue no longer catalogues is left out, unless it
is the one in use.

# Brightside — Design guidelines

Brightside is a **consumer product** that happens to be powered by the Covia
Grid. Its job is to make a full Covia venue feel like a friendly personal
assistant. These are short rules of thumb; when in doubt, favour the person in
front of the screen over the platform behind it.

## 1. Purpose

Demonstrate the power of the Covia Grid and lattice technology **as a personal
agent** — a real venue, real agents, real persistent lattice state, all running
privately on the user's own machine. The platform should be *felt* (it just
works, it remembers, it's private), not *explained*.

## 2. Speak like a product, not a platform

- **No jargon in the main flow.** The words *venue*, *DID*, *principal*,
  *operation*, *adapter*, *lattice*, *agent*, *job* never appear on the screens
  a normal person uses. Say "your assistant", "your name", "settings",
  "memory", "starting up".
- Friendly, plain, second person. "What should I call you?", not "Enter a user
  identity".
- Errors are human: *"Brightside couldn't start up — details are in the logs"*,
  never a stack trace or a `did:key:…` in the face.

## 3. Hide the machinery, don't remove it

The Covia power is the point — keep it reachable, just not in the way.

- Technical surfaces (the web dashboard, the identity/DID, the local URL, the
  settings file, logs) live under an **Advanced** menu and the **About** box.
- That's where a curious user or a demo can pull back the curtain and see the
  venue, the grid, the DID. The everyday user never has to.

## 4. Identity

- The user picks a **name**, nothing more. Internally that becomes a venue
  principal `u:<name>`; the user only ever sees the name.
- Store the name in `~/.brightside/identity.json`, apart from the hand-edited
  `config.json`, so choosing a name never rewrites their settings.

## 5. The assistant itself

- Behaviour and knowledge that shape the assistant belong in **Covia skills**
  (e.g. the `introduction` skill), not hard-coded prose — it shows the platform
  off and keeps the persona editable as data.
- Give the assistant a **memory** (`n/memory`) so it feels like *their*
  assistant across sessions. It should remember quietly and never narrate the
  mechanics.
- The system prompt stays small: identity, tone, and pointers to skills and
  memory. Detail lives in skills.

### Namespaces

Use Covia's namespaces for what they're for:

| Namespace | Purpose | Written by |
|-----------|---------|-----------|
| `v/skills/brightside/…` | Brightside's **default, shipped skills** (e.g. `introduction`) | Brightside, as the venue principal, on startup |
| `w/skills` | The **user's own** skills, developed over time | the user (their agent) |
| `n/…` | The assistant's **private scratch space**, including `n/memory` | the assistant, during a run |

Only the venue may write `v/`; the user develops in `w/`; scratch and memory
live in `n/`.

## 6. Onboarding

- First launch is a warm welcome screen, not a dialog box. Let people start
  typing their name immediately while the venue boots in the background.
- Returning users skip straight to the chat.

## 7. Defaults that just work

- Sensible defaults for everything; an empty config is valid.
- Private by default: loopback-only, state on the user's own disk.
- Nothing the user must configure before the first conversation (beyond a model
  API key, which the packaged build provisions).

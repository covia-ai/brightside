# Brightside — Design guidelines

Brightside is a **self-sovereign personal agent** — a real consumer product that
happens to be powered by the Covia Grid. Its job is to be genuinely useful to the
person who owns it, privately on their own machine. These are short rules of
thumb; when in doubt, favour the person in front of the screen over the platform
behind it.

## 1. Purpose

Be a **powerful personal agent the person owns** — one that solves real problems,
remembers what matters to them, grows with their needs, and answers to them
alone, all running privately on their own machine. Covia is the remarkable
technology that makes this possible, but it is the means, not the message: the
platform should be *felt* (it just works, it remembers, it's private, it's
yours), not shown off or explained.

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

The power underneath is real — keep it reachable for those who want it, just not
in the way.

- Technical surfaces (the web dashboard, the identity/DID, the local URL, the
  settings file and logs) live in **Settings**, with desktop tools and **About**
  on the **General** page.
- That's where a curious or technical user can pull back the curtain and see the
  venue, the grid, the DID. The everyday user never has to.

## 4. Identity

- The user picks a **name**, nothing more. Internally that becomes a venue
  principal `u:<slug>`; the user only ever sees the name. The slug is fixed
  once chosen — changing the name later never changes *which* assistant (and
  memory, and skills) they are talking to.
- Store the name, stable slug and full Covia user DID in
  `~/.brightside/identity.json`, apart from the hand-edited `config.json`, so
  choosing a name never rewrites settings and the public identity is explicit
  recovery metadata.

## 5. The assistant itself

- Optional behaviour and working methods belong in discoverable **Covia
  skills**, including the on-demand `introduction` skill. No shipped skill is
  pinned by default; precise descriptions determine when each one loads.
- Give the assistant a **memory** (`n/memory`) so it feels like *their*
  assistant across sessions. It should remember quietly and never narrate the
  mechanics.
- The configured system prompt owns the assistant's identity and role. Dynamic
  owner/product facts are assembled by the read-only `brightside:context`
  operation through a non-skill `config.loads` entry. Task detail lives in
  skills.
- **It can grow.** The assistant can create, refine and remove its own skills in `w/skills` —
  that's how it "upgrades" itself. Expose the ability the way a good product
  gates power: an on-demand *skills* skill explains growing abilities and
  reveals a *skill-authoring* sub-skill, which is the only thing that grants the
  write tool and a Brightside operation restricted to deleting one named skill.
  So self-improvement is possible, discoverable and reversible, but the ability
  to mutate skills isn't loaded until the assistant deliberately reaches for it.
- **It learns from misses.** A narrowly scoped always-available operation appends
  concrete load failures, missing skills and instruction conflicts beneath
  `w/skill-feedback/<job-id>`. The model cannot choose another path, and ordinary
  task failures do not become backlog noise.

### Namespaces

Use Covia's namespaces for what they're for:

| Namespace | Purpose | Written by |
|-----------|---------|-----------|
| `v/skills/brightside/…` | Brightside's **default, shipped skills** (introductions, self-authoring and everyday work) | `BrightsideSkillsAdapter`, at venue launch |
| `w/skills` | The **user's own** skills, developed over time | the user (their agent) |
| `w/skill-feedback` | The agent's private append-only skill-system backlog | `BrightsideAdapter` |
| `n/…` | The assistant's **private scratch space**, including `n/memory` | the assistant, during a run |

Only the venue may write `v/`; the user develops in `w/`; scratch and memory
live in `n/`.

## 6. Onboarding

- First launch is a warm welcome screen, not a dialog box. Let people start
  typing their name immediately while the venue boots in the background.
- Returning users skip straight to a clean Home chat. Saved conversations stay
  available under Sessions, but Brightside does not resume one implicitly or
  create a new session until the user sends a message.

## 7. Defaults that just work

- Sensible defaults for everything; an empty config is valid.
- Private by default: loopback-only, state on the user's own disk.
- Nothing the user must configure before the first conversation (beyond a model
  API key, which the packaged build provisions).

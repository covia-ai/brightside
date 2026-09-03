# Skills, tools and memory

How Brightside's assistant gets its abilities: a small always-on core, three
libraries of skills it loads when a task calls for one, and a private memory.
This is the design; the shipped library itself is `BrightsideSkillsAdapter`
and the skill files under `src/main/resources/brightside/skills/`, and the
mechanism is Covia's (`../covia/venue/docs/SKILLS.md`).

- **Discovery is broad; authority is deliberate.** The assistant can see its
  owner's, Brightside's and the venue's libraries, but starts every turn with
  only read-only lattice reads, memory and the skill-feedback reporter. Every
  other tool arrives by loading the skill that carries it.
- **Every top-level skill is a useful first load.** The assistant is
  general-purpose, and loading a skill is its first step towards specialising
  for a task. A skill therefore solves the general form of its problem itself
  and reveals children for the specific sub-issues. No shipped skill is a bare
  router.
- **Descriptions are the trigger.** An index line says which tasks the skill
  serves and when to load it, in the words the owner would use, and never
  which tools it carries. It is all the model sees before loading.
- **Skills teach judgement, not mechanics.** How a tool behaves is its
  schema's job. A skill body says when to act, what good looks like and what
  never to do.
- **Namespaces separate who writes what.** The venue writes `v/`, the owner's
  agent writes `w/`, scratch and memory live in `n/`.

## Namespaces

| Namespace | Purpose | Written by |
|---|---|---|
| `v/skills/brightside/…` | Brightside's shipped skills | `BrightsideSkillsAdapter`, at venue launch |
| `v/skills/root` | the venue's own library — its entry points, each opening a family | the venue |
| `w/skills` | the owner's agent's own skills, including imported `SKILL.md` folders | the agent, `FilesystemSkills` |
| `w/skill-feedback/<id>` | append-only reports of concrete skill-system misses | the scoped feedback operation |
| `n/…` | the agent's private scratch, including `n/memory` | the agent |

Only the venue writes `v/`. A skill of the owner's shadows a shipped skill of
the same name, and Brightside's shadow the venue's.

## What the assistant starts with

`ChatSession` configures the agent the same way on every launch:

- **Skillsets** `w/skills`, `v/skills/brightside`, `v/skills/root`, in that
  order. No skill is pinned.
- **Tools** the memory tool and the skill-feedback reporter, plus the default
  read-only lattice reads.
- **Context** its memory at `n/memory`, rendered every turn through the
  read-only recall operation, and one non-skill load, `brightside-context`,
  which the read-only `brightside:context` operation assembles: the owner's
  name and DID, where model processing happens, and the product invariants.

The configured system prompt owns the assistant's name and role. Runtime facts
come from that context load. Working methods and everything optional stay in
skills. Those three jobs are kept apart on purpose.

## How a load works

- The `[Skills]` index — one line per discoverable skill — is in the context on
  every turn. That line is the whole always-on cost of a skill.
- Loading appends the skill's body to the conversation once and adds its tools
  to the manifest for the rest of the session. Nothing is declared ahead of a
  load.
- A loaded skill's children join the index and can be loaded by name while
  the parent stays loaded. Children are discoverable, never auto-loaded.
- Unloading retracts a skill's tools and the children it revealed. Its body
  stays in history, so no shipped skill tells the agent to unload.
- The default assistant's base turn is around 16 KB: harness tools, the two
  always-on tools, the context load and the index.

## The shipped library

Each top-level skill is a useful first load; the indented ones are children it
reveals. Descriptions live in the skill files; this is the shape.

- `conversations` — how it talks with the owner, and reading, summarising or
  compacting past conversations.
- `skills` — how it grows and surveys skills → `skill-authoring` (the write
  and delete tools for `w/skills`), Covia's `skill-import`.
- `lattice` — structured data and its scopes, with the edit tools → Covia's
  `assets`, `secrets`.
- `files` — Brightside's file roots with the file tools → `vault`, `dlfs`.
- `diagnostics` — job records with the read-only and job tools → `sessions`,
  `brightside-logs`.
- `harness` — how Brightside works internally → `etch`, `convex-lattice`.
- `automation` — reminders and routines with the scheduler tools → Covia's
  `tasks` and `orchestration`, Brightside's `hitl`.
- `hitl` — asking the owner through the Inbox. Shadows Covia's skill of the
  same name: judgement about what the owner sees and when to ask.
- `administration` — asking Odin for what needs operator rights
  ([ODIN.md](ODIN.md)).
- `convex` — the Convex network with the free query tool → `accounts`,
  `convex-lisp`, `smart-contracts` (→ `trust`), `cns`, `costs`, `security`,
  `cpos-consensus`, `cad3-data`, `protonet`, `ecosystem`, `convex-lattice`.
- `research` — evidence-led research with the web tools → Covia's `http` for
  credentialed APIs. The untrusted-content boundary is Brightside's judgement
  and lives here.
- `writing`, `planning`, `coding` — working methods; no tools.
- `moltbook` — taking part on Moltbook with its operations → `moltbook-setup`.
- `introduction` — greeting guidance, loaded rather than pinned.

The venue's `v/skills/root` entry points (`agents`, `grid`, `auth`,
`connections`, `venue`, `discovery`, `covia`, `workspace`) sit beside these in
the index and open their own families.

## Growing, importing and feedback

- The assistant writes its own skills to `w/skills/<name>`; `skill-authoring`
  is the only skill that brings the write tool, and deletion goes through a
  Brightside operation that can address one named skill and nothing else. So
  self-improvement is discoverable and reversible, and the power to change
  skills is never in context until deliberately reached for.
- Folders of `SKILL.md` files under `~/.brightside/skills/` are imported into
  `w/skills` at launch (`FilesystemSkills`), unchanged when nothing changed.
- A narrow always-on operation appends concrete misses — a failed load, a
  missing skill, an instruction that contradicts observed tools — under
  `w/skill-feedback/<job-id>`. It chooses the path itself and cannot write
  elsewhere. Ordinary task failures are not backlog.

## Tests

`BrightsideSkillsTest` is driven by the shipped list: every skill resolves,
every tool it declares exists, every child it reveals loads with its tools
including the venue skills it points at, and no top-level skill reveals
children without tools of its own. Nothing asserts prose.

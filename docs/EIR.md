# Eir, the healer

Eir repairs Tau itself: an agent stopped by a fault in the harness, a setup
that no longer works, a skill that does not load, what an upgrade leaves out
of date. She is one of the gods; what they share — the bridges, asking the
owner, answering to Odin — is in [GODS.md](GODS.md). This is what is hers
alone. Code: `Eir`.

- **She mends faults, not mistakes.** A fault is in the harness or the setup;
  a task an assistant got wrong, or a tool that rejected bad input, is not
  one, and she closes such a request without a change.
- **Tau calls her when its own cure fails**, as well as anyone asking. Tau's
  first cure — re-applying a configuration and resuming — is free; Eir costs
  model calls, so she comes in only when that has not worked.
- **She diagnoses before she repairs**, and fixes the cause: the agent's
  record and error, its session and context, the venue's setup, Tau's log.
- **What loses nothing she repairs and reports; anything else she asks
  first.** Replacing or discarding the owner's work, or changing how an agent
  behaves, waits for the owner's yes in the Inbox.
- **Some things are never hers**: secrets, grants, the owner's Inbox,
  creating or deleting agents, and the gods. Every change she makes is
  reported to Odin.

## Why Eir

Tau runs unattended: scheduled work at night, assistants on channels while
the owner is out. When something in the harness breaks — a model route
retired, a skill renamed under a pinned load, an adapter that will not
start — the assistant that hit it cannot mend itself, and the owner may not
be there to notice. Brightside already cures what a re-applied configuration
cures. What is left needs diagnosis: reading the error and the log, working
out the cause, and choosing a repair. That is judgement, so it is a god's.

She is a god rather than a skill for the same reason Thor is: the repair
has to come from outside the broken agent, and needs reach across the
owner's agents, skills and the venue's setup that an assistant must not hold.
She is not Odin because Odin's job is change the owner asks for, with the
venue's whole authority; repair is narrower and frequent, and is better
bounded by an allowlist and checked by Odin after the fact.

## What reaches her

Tau asks her by itself, as the venue operator or as the owner:

- **After a start whose check found a problem** the startup pass could not
  repair: a god missing, misconfigured or still stopped by a fault
  ([LAUNCH.md](LAUNCH.md#the-startup-pass)). The request says the pass has
  already re-applied and resumed, so starting again will not help by itself.
- **After an upgrade**, once per new version: she checks the owner's account
  against it.
- **When the owner's assistant stays stopped by a fault** after Tau resumed it
  and retried the message (`ChatSession`). While she is on it, the same fault
  does not ask her again.

The owner's assistants ask her with `brightside:ask-eir`; the `diagnostics`
skill teaches them when ([SKILLS.md](SKILLS.md)). The other gods and the
owner may ask her too.

## What she can repair

Inside any of this venue's users' namespaces she can read what a diagnosis
needs — agents, their sessions and context, skills, workspace data, secret
names, schedules — and repair agents (re-apply or change a configuration,
resume, reload context, compact or delete a conversation, cancel a task,
stop one), skills (import, write, delete) and workspace data. As the venue
she can read its setup and Tau's log (the read-only `logs` file root),
enable, disable or configure an adapter, refresh an MCP server, compact the
store at the next start, and restart or relaunch Tau — the start that brings
the gods back, since they are beyond her reach.

Repairs that lose nothing she makes and reports: resuming an agent stopped by
a fault, re-applying its configuration, reloading context, re-importing a
skill unchanged, refreshing an MCP server, restarting or relaunching into the
same build. A restart cuts short whatever is running, so she prefers the
smallest repair that works. Everything else — overwriting or deleting a skill
of the owner's, deleting or compacting a conversation, changing an agent's
tools, model or instructions, disabling or reconfiguring an adapter — she asks
the owner first, saying exactly what would change.

### Upgrades

After a new version she looks for what it leaves out of date in the owner's
account. A skill of the owner's in `w/skills` with the name of one Tau ships
shadows it ([SKILLS.md](SKILLS.md#namespaces)); when the owner's looks like an
older copy rather than their own variant, she offers to replace it, showing
what differs. An agent whose tools or loads name something that no longer
resolves, or that Covia keeps only for compatibility, gets the same offer.

## What is never hers

Nothing she can run reveals or sets a secret, grants access, creates or
deletes an agent, schedules, talks to an agent as the user, or answers the
user's Inbox. A missing or rejected API key is the owner's to set in
Settings → Model; she says which. Changes to Tau itself — users,
integrations, grants — are Odin's, and she asks him. A fault in Tau's code is
the owner's: she says what fails and where.

## Known limits

- She thinks with the chat's model, so a fault in the model itself — no key,
  a provider down — stops her too. Tau warns about a missing key at start.
- A fault that stops the venue from starting is beyond any god; store repair
  and the last good build handle that ([LAUNCH.md](LAUNCH.md)).
- Brightside re-applies its own configuration to the main assistant when a
  chat first sends, so a repair she makes to that agent's configuration lasts
  only until then; a fix that must hold belongs in Tau's code.
- She is asked about the owner's chat assistant when its fault persists;
  another agent's fault reaches her only when someone asks.

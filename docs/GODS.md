# The gods

Brightside gives its owner a team of super-user agents, the gods, working for
them beside their everyday assistants. Each god holds a power the assistants
deliberately lack, and the judgement to use it on the owner's behalf. This
doc is the idea and what the gods share; each god has its own doc for its
design and motivation.

- **Assistants stay ordinary on purpose.** They act with the owner's
  authority inside the owner's namespace, and they read untrusted content all
  day. Anything beyond that — administering the venue, fighting an attack,
  moving money — sits with a god instead.
- **A god is the venue's own agent.** It belongs to the venue principal, not
  to the owner's user, and answers to the owner alone.
- **Power is caps; know-how is skills; judgement is a prompt.** What a god
  *can* do is its standing Covia caps, which Covia checks at every point of
  action. Its tools come with the skills its job calls for, and a tool its
  caps do not cover does nothing. What a god *should* do is its system
  prompt.
- **They check one another.** Gods that act on their own judgement report
  every change to Odin straight after. He takes a disagreement to the owner,
  who breaks the tie.
- **The owner decides what is theirs to decide**, through the Inbox, with the
  decision laid out for them.

## The team

| God | Job | Doc |
|---|---|---|
| Odin, the administrator | changes that need administrator rights; the wiser head the others answer to | [ODIN.md](ODIN.md) |
| Thor, the guardian | responds to reported threats; can stop and restrict agents at once | [THOR.md](THOR.md) |
| Njord, the treasurer | keeps the signing keys; every financial transaction goes through him | [NJORD.md](NJORD.md) |
| Eir, the healer | repairs faults in Tau itself; Tau calls her when its own cure fails | [EIR.md](EIR.md) |

They work as one team. An assistant that meets an attack tells Thor. One that
needs a change it cannot make asks Odin, one that needs to pay asks Njord,
and one that meets a fault in Tau asks Eir. Odin and Njord tell Thor when a
request looks like an attack, and Thor, Njord and Eir answer to Odin for
what they do.

## When a job deserves a god

A job earns a god only if it needs one of three things: authority the
assistants should not hold, independence from the agents it acts on, or a
reach across the whole venue rather than one conversation. Everything else
belongs in a skill or an ordinary agent. Every god turn is a model call, so
the team stays small and its work lean. A report that changes nothing
should trouble no one, and a god that would mostly be idle waits until its
job exists. Anything that makes a god act on its own, such as a watch or a
schedule, starts as one rule that is nearly certain when it fires: each
firing is a turn, and turns cascade from god to god and on to the owner's
Inbox.

Ideas that may join when their job arrives, none of them built:

- **Heimdall**, the gatekeeper: who may reach the venue from outside, once
  venues meet venues ([NETWORK.md](NETWORK.md)).
- **Idunn**, upkeep: store health, old sessions and failed jobs. A duty of
  Odin's until it outgrows him.
- **Mímir**, memory: consolidation across agents, if memory grows beyond
  what each agent keeps ([ADVANCED_MEMORY.md](ADVANCED_MEMORY.md)).
- **Loki**, the red team: probing the gods' and assistants' defences. Only
  ever on a sandbox venue.

## What every god shares

| | |
|---|---|
| Owner | the venue principal, not the user |
| Address | `<venueDID>/g/<name>` |
| Created | when first needed — the first message, acting as the operator, a model change — never at launch, which submits no jobs; once configured, keeping a god current costs no job |
| Model | the chat's chosen model |
| Memory | `n/memory` under the venue: the owner's decisions, and what the god has learnt |
| Skills | the venue's own library, `v/skills/root`, and by name the skills its job calls for |
| Caps | every god's, below, and its own powers |
| Visible | only while acting as the venue operator (*Settings → Identity → Switch user*) |

`God` is one god's definition and `Gods` lists them. A new god is a
definition, its two bridge resources and a place in that list.

### Caps and skills

Gods exist because assistants cannot act as the venue. A god is the venue's
agent, so its caps narrow the venue's authority, and Covia admits it to an
administrative operation when its caps include that operation's ability
(`Engine.hasVenueAuthority`). Every god may call any operation, read
everything the venue holds (its data, setup, jobs, agents, the names of its
secrets, and every file root, Tau's log among them), keep its own notes and
ask through the Inbox. None writes the venue's state beyond its notes. Beyond that, each
holds its own powers, named as Covia names them:

| God | Powers |
|---|---|
| Odin | `admin/user/create` and `admin/user/read` on `users`; `admin/adapter/manage` on `adapters`; `p2p/read` and `p2p/manage`; `secret/write` and `crud/delete` on the venue's `s/`; `admin/venue/restart` on `process` (restart, relaunch, shutdown); `admin/venue/compact` on `store` |
| Thor | `admin/user/read` on `users` |
| Eir | `admin/user/read` on `users` |
| Njord | none: his treasury is granted per call |

`admin/venue/compact` is Brightside's leaf of Covia's `admin/venue` family,
for compacting the store at the next start, apart from `admin/venue/gc`,
which collects it while it runs ([LAUNCH.md](LAUNCH.md#compacting-the-store)).
Brightside sets each god's caps with the rest of its configuration at every
launch, before any god can act ([LAUNCH.md](LAUNCH.md#the-startup-pass)), and
whenever the model changes (`God`). `GodCapsTest` shows Covia refusing what
they leave out.

A god's tools are the few it always needs (its bridges, the Inbox, its jobs,
the reads its job turns on) and those of the skills it loads. Beside the
venue's library it is offered, by name, the skills its job calls for: Covia's
users, adapters, secrets, MCP and capabilities skills for Odin; users for
Thor; Brightside's log and session skills, adapters and MCP for Eir; Convex
for Njord. A skill may bring tools its caps do not cover; Covia refuses them.

### The bridges

Each god has two narrow Brightside operations for what caps do not cover.

**Ask** (`brightside:ask-odin`, `brightside:alert-thor`,
`brightside:ask-njord`, `brightside:ask-eir`) is callable by any authenticated user of the venue,
or their agents. It mints a short-lived, venue-rooted grant letting the
calling user make a request of the god, and submits it as that user. The task
is the user's own job, and Covia attributes the caller natively. Brightside
stamps who asked from the authenticated context, so a request cannot forge
it; the request text itself is untrusted. The bridge returns the god's
outcome, or a started snapshot while the god is still deciding.

**Run** (`brightside:odin-run`, `brightside:thor-run`,
`brightside:njord-run`, `brightside:eir-run`) is callable by that god alone. It lends the
venue's authority where standing caps cannot yet say what the god may do.
Without a user it runs one of the god's venue operations: for Thor and Eir,
a change Odin must hear of (below); for Odin, what needs a check Covia does
not make or would need a cap far wider than the act ([ODIN.md](ODIN.md)).
What a god's caps let it do itself, its bridge does not lend. With a user,
one of this venue's own, it runs one of the god's namespace operations
inside that user's namespace on their behalf through Covia's `user:sudo`. A
per-call delegation bounds it to that namespace, so the venue's own authority
does not leak in. The job record says "venue, on behalf of the user". A seed
goes by reference only; the bridge refuses a literal one. A god may also have
granted operations, which run as the god itself under a grant the venue signs
for exactly what that call touches, named by the operation's adapter
(`CallGrants`): Njord's treasury operations are these
([TREASURY.md](TREASURY.md)).

**What is left of the run bridge gives way as Covia learns to say it.** No
standing cap reaches another user's namespace, so acting for a user needs
the bridge's sudo, and accounts no administrator agent may delete or act for
are asked of Covia ([covia#703](https://github.com/covia-ai/covia/issues/703)).
Withdrawing a god's own Inbox request waits on
[covia#702](https://github.com/covia-ai/covia/issues/702). An overseer that
watches its gods' jobs would let Thor's and Eir's changes go direct too. The
move is tracked in
[brightside#40](https://github.com/covia-ai/brightside/issues/40). Each god's
caps are the owner's design call, so the improvement loop leaves them alone.

### Answering to an overseer

A god may have an overseer; Thor's, Njord's and Eir's is Odin. Then anything the
venue does not declare read-only needs a reason. Once it is done, the run
bridge reports it — operation, input, outcome and reason — to the overseer
through the overseer's ask bridge, as the god, without waiting for a
verdict. A report that cannot be sent is logged; the change stands either
way.

### A user of its own

A god may keep a user of its own, which only its run bridge may act as and no
other god's may enter, and it makes keys there alone. Njord keeps the
treasury this way ([IDENTITY.md](IDENTITY.md)); the god's own ensure
registers it.

### Asking the owner

A god asks its own owner, the venue, so its requests land in the venue's
inbox. The person at the keyboard is the operator, so Brightside shows that
inbox merged with the owner's own in the Inbox tab. Each request is marked
with the god who asked, and Brightside answers it as the operator. The
answer reaches the god in the conversation that asked, whenever it comes. A
god withdraws a request of its own once it no longer needs an answer, through
its run bridge, which cancels the request's job and nothing else; the request
then shows as cancelled.

### Deliberate stops

Covia suspends an agent when its setup or a fault prevents further work,
and when someone stops it with `agent:suspend`. An ordinary task failure
fails that task and lets the agent continue. Brightside resumes a fault
suspension by itself once the
configuration is re-applied, which is the usual cure; when that does not
cure the owner's assistant, Brightside asks Eir ([EIR.md](EIR.md)). It never lifts the
second: the chat with an agent stopped on purpose says so and points to the
Inbox, and a god stopped on purpose stays stopped. `Suspension` tells the two
apart.

Suspension preserves queued tasks for an explicit resume. A chat message
already taken by the stopped cycle fails with the reason for the stop.

`OdinTest`, `ThorTest`, `NjordTest` and `EirTest` exercise all of this against a real
venue.

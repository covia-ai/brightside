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
- **Power is a mechanism; judgement is a prompt.** What a god *can* do is an
  allowlist enforced by its bridge. What it *should* do is its system prompt.
  Neither is trusted to do the other's job.
- **They check one another.** Gods that act on their own judgement report
  every change to Odin straight after. He takes a disagreement to the owner,
  who breaks the tie.
- **The owner decides what is theirs to decide**, through the Inbox, with the
  decision laid out for them.

## The team

| God | Job | Doc |
|---|---|---|
| Odin, the administrator | changes that need administrator rights; the wiser head the others answer to | [ODIN.md](ODIN.md) |
| Thor, the guardian | protects the owner against adversaries; can stop and restrict agents at once | [THOR.md](THOR.md) |
| Njord, the treasurer | keeps the signing keys; every financial transaction goes through him | [NJORD.md](NJORD.md) |

They work as one team. An assistant that meets an attack tells Thor. One that
needs a change it cannot make asks Odin, and one that needs to pay asks
Njord. Odin and Njord tell Thor when a request looks like an attack, and
Thor and Njord answer to Odin for what they do.

## When a job deserves a god

A job earns a god only if it needs one of three things: authority the
assistants should not hold, independence from the agents it watches, or a
reach across the whole venue rather than one conversation. Everything else
belongs in a skill or an ordinary agent. Every god turn is a model call, so
the team stays small and its work lean. A report that changes nothing
should trouble no one, and a god that would mostly be idle waits until its
job exists.

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
| Skills | the venue's own library, `v/skills/root` |
| Visible | only while acting as the venue operator (*Settings → Identity → Switch user*) |

`God` is one god's definition and `Gods` lists them. A new god is a
definition, its two bridge resources and a place in that list.

### The bridges

Gods exist because assistants cannot act as the venue: venue authority is
admitted only to the venue itself, never to an agent it owns, and an agent's
run loop carries no delegations. Each god has two narrow Brightside
operations to bridge the gap.

**Ask** (`brightside:ask-odin`, `brightside:alert-thor`,
`brightside:ask-njord`) is callable by any authenticated user of the venue,
or their agents. It mints a short-lived, venue-rooted grant letting the
calling user make a request of the god, and submits it as that user. The task
is the user's own job, and Covia attributes the caller natively. Brightside
stamps who asked from the authenticated context, so a request cannot forge
it; the request text itself is untrusted. The bridge returns the god's
outcome, or a started snapshot while the god is still deciding.

**Run** (`brightside:odin-run`, `brightside:thor-run`,
`brightside:njord-run`) is callable by that god alone. Without a user it runs
one of the god's venue operations under the venue's authority. With a user,
one of this venue's own, it runs one of the god's namespace operations
inside that user's namespace on their behalf through Covia's `user:sudo`. A
per-call delegation bounds it to that namespace, so the venue's own authority
does not leak in. The job record says "venue, on behalf of the user". A seed
goes by reference only; the bridge refuses a literal one.

### Answering to an overseer

A god may have an overseer; Thor's and Njord's is Odin. Then anything the
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
answer reaches the god in the conversation that asked, whenever it comes.

### Deliberate stops

Covia suspends an agent when a turn fails, and when someone stops it with
`agent:suspend`. Brightside resumes the first kind by itself once the
configuration is re-applied, which is the usual cure. It never lifts the
second: the chat with an agent stopped on purpose says so and points to the
Inbox, and a god stopped on purpose stays stopped. `Suspension` tells the two
apart.

`OdinTest`, `ThorTest` and `NjordTest` exercise all of this against a real
venue.

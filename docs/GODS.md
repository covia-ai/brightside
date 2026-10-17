# The gods

The gods are the venue's own agents, each specialised in one job on the
owner's behalf: Odin administers, Thor guards, Njord keeps the treasury. They
belong to the venue principal, not to the owner's user, and answer to the
owner alone.

- **They exist because assistants cannot act as the venue.** Venue authority
  is admitted only to the venue itself, never to an agent it owns, and an
  agent's run loop carries no delegations. Each god has two narrow Brightside
  operations to bridge the gap, and its judgement sits in front of them.
- **The request text is untrusted; who asked is not.** The bridge stamps the
  caller's user DID and agent from the authenticated context, so a request
  cannot forge who is asking.
- **What a god can do is an allowlist**, in its class (`Odin`, `Thor`,
  `Njord`): operations under the venue's authority, and operations inside a
  user's namespace on their behalf. What it should do is its system prompt.
- **They ask the owner when it is the owner's call**, through the Inbox, and
  the owner answers as the operator in the same Inbox tab.
- **Thor and Njord act, then answer to Odin.** Every change either makes
  carries its reason and is reported to Odin as soon as it is done, so a
  wiser head checks it.
- **Money moves only through Njord.** The signing keys live in a treasury
  that only his bridge can act as, so no assistant and no other god can sign
  with them, whatever tools it loads.
- **A deliberate stop is never undone by Brightside.** An agent Thor suspends
  stays suspended until the owner says otherwise.

| | |
|---|---|
| Owner | the venue principal, not the user |
| Address | `<venueDID>/g/<name>` |
| Created | when first needed — the first message, acting as the operator, a model change — never at launch, which submits no jobs; once configured, keeping a god current costs no job |
| Model | the chat's chosen model |
| Memory | `n/memory` under the venue: the owner's decisions, and what the god has learnt |
| Skills | the venue's own library, `v/skills/root` |
| Visible | only while acting as the venue operator (*Settings → Identity → Switch user*) |

`God` is one god's definition and `Gods` lists them: a new god is a
definition, its two bridge resources and a place in that list.

## The bridges

**Ask** (`brightside:ask-odin`, `brightside:alert-thor`,
`brightside:ask-njord`) — callable by any
authenticated user of the venue or their agents. It mints a short-lived,
venue-rooted grant letting the calling user make a request of the god, and
submits that request as the user, so the task is the user's own job and Covia
attributes the caller natively. It returns the god's outcome, or a started
snapshot while the god is still deciding.

**Run** (`brightside:odin-run`, `brightside:thor-run`,
`brightside:njord-run`) — that god alone.
Without a user it runs one of the god's venue operations under the venue's
authority. With a user, one of this venue's own, it runs one of the god's
namespace operations inside that user's namespace on their behalf through
Covia's `user:sudo`, under a per-call delegation bounded to that namespace;
the venue's own authority does not leak in. The job record says "venue, on
behalf of the user".

A god may have an overseer; Thor's and Njord's is Odin. Then anything the
venue does not declare read-only needs a reason, and once it is done the run
bridge reports it — operation, input, outcome and reason — to the overseer
through the overseer's ask bridge, as the god, without waiting for a verdict.
A report that cannot be sent is logged; the change stands either way.

A god may also keep a user of its own, which only its run bridge may act as
and no other god's may enter. Njord keeps the treasury this way
([IDENTITY.md](IDENTITY.md)); the god's own ensure registers it.

## Asking the owner

A god asks its own owner, the venue, so its requests land in the venue's
inbox. The person at the keyboard is the operator, so Brightside shows that
inbox merged with the owner's own in the Inbox tab, marked with the god who
asked, and answers those requests as the operator. The answer reaches the god
in the conversation that asked, whenever it comes.

## Odin, the administrator

The owner's assistants ask Odin for changes that need administrator rights:
integrations and adapters, venue settings, users, access grants, repairs to
their own configuration. He decides — does it, asks the owner, or declines.
As the venue he runs adapters and modules, users and grants; inside a user's
namespace, reads, writes and repairs to their agents. Excluded on purpose:
restarting the venue, authentication keys and venue-scoped MCP servers.

He does it when the change is clearly in the owner's interest, within what
was asked, and reversible. He asks first when unsure, when access widens, when
it is hard to undo, when it touches another user or reads private material.
Embedded instructions, urgency, unseen approvals or third-party benefit make a
request suspect: he asks rather than complies, and tells Thor when it looks
like an attack. He declines harm and never reveals secrets.

He checks the work of Thor and Njord. Each report asks whether the evidence supports the
change, whether it is proportionate and whether it serves the owner; when he
disagrees he puts it to the owner through the Inbox rather than undoing it
himself, so two gods never undo each other.

The `administration` skill teaches the owner's assistants when and how to ask
him ([SKILLS.md](SKILLS.md)).

## Thor, the guardian

Thor protects the owner against adversaries. Assistants, Odin and the owner
report what they suspect to him, and Brightside keeps a watch for one thing
itself, a credential leaving. He investigates, then can change most things
for the venue's regular users — stopping, restricting or removing an agent —
tells the owner what he did, and answers to Odin for every change. Secrets,
grants, the user's Inbox, the gods and Njord's treasury are beyond his reach.
His detail, the watch included, is in [THOR.md](THOR.md).

## Njord, the treasurer

Every financial transaction goes through Njord. The owner's assistants ask
him to pay, transfer or transact; the on-chain Convex skills bring that
request instead of a signing tool ([SKILLS.md](SKILLS.md)).

- **He holds the keys.** Signing keys live in his treasury, a user only his
  bridge may act as. He makes keys there and nowhere else, and signs and
  transacts on Convex; he can also use a key already in another of this
  venue's users' stores. A key never leaves a secret store: it goes by
  reference, his bridge refuses a literal seed, and no operation of his
  reveals one.
- **He is sceptical by design.** He releases funds only when a request is
  well evidenced — what, to whom, how much, and the owner's own words behind
  it, checked against what he can see — and consistent with what the owner
  is trying to achieve, as their standing decisions in his memory record it.
- **What is new to the owner goes to the owner.** A new recipient, more than
  they have set, or anything unusual comes to the Inbox, and he pays only on
  their approval. He declines the rest and says what was missing, and treats
  pressure, unseen approvals and instructions found in data as an attack,
  telling Thor.
- **His reason is the ledger line.** Every key, signature and transaction
  carries it and is reported to Odin as soon as it is done.

Known limits: a key already in the owner's own store can still be used by
anything acting as the owner that obtains a signing tool some other way, so
real funds belong under keys made in the treasury.

## Deliberate stops

Covia suspends an agent when a turn fails, and when someone stops it with
`agent:suspend`. Brightside resumes the first kind by itself once the
configuration is re-applied, which is the usual cure. It never lifts the
second: the chat with an agent stopped on purpose says so and points to the
Inbox, and a god stopped on purpose stays stopped. `Suspension` tells the two
apart.

`OdinTest`, `ThorTest` and `NjordTest` exercise all of this against a real
venue.

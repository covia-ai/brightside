# The gods

The gods are the venue's own agents, each specialised in one job on the
owner's behalf: Odin administers, Thor guards. They belong to the venue
principal, not to the owner's user, and answer to the owner alone.

- **They exist because assistants cannot act as the venue.** Venue authority
  is admitted only to the venue itself, never to an agent it owns, and an
  agent's run loop carries no delegations. Each god has two narrow Brightside
  operations to bridge the gap, and its judgement sits in front of them.
- **The request text is untrusted; who asked is not.** The bridge stamps the
  caller's user DID and agent from the authenticated context, so a request
  cannot forge who is asking.
- **What a god can do is an allowlist**, in its class (`Odin`, `Thor`):
  operations under the venue's authority, and operations inside a user's
  namespace on their behalf. What it should do is its system prompt.
- **They ask the owner when it is the owner's call**, through the Inbox, and
  the owner answers as the operator in the same Inbox tab.
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

**Ask** (`brightside:ask-odin`, `brightside:alert-thor`) — callable by any
authenticated user of the venue or their agents. It mints a short-lived,
venue-rooted grant letting the calling user make a request of the god, and
submits that request as the user, so the task is the user's own job and Covia
attributes the caller natively. It returns the god's outcome, or a started
snapshot while the god is still deciding.

**Run** (`brightside:odin-run`, `brightside:thor-run`) — that god alone.
Without a user it runs one of the god's venue operations under the venue's
authority. With a user, one of this venue's own, it runs one of the god's
namespace operations inside that user's namespace on their behalf through
Covia's `user:sudo`, under a per-call delegation bounded to that namespace;
the venue's own authority does not leak in. The job record says "venue, on
behalf of the user".

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

The `administration` skill teaches the owner's assistants when and how to ask
him ([SKILLS.md](SKILLS.md)).

## Thor, the guardian

Thor protects the owner against adversaries: instructions smuggled into
content that turn an assistant against them, an agent acting beyond what they
asked, outsiders probing for access or pressing for secrets. Assistants, Odin
and the owner report what they suspect to him; the `security` skill teaches
assistants when.

- **He investigates before he acts**, reading the agent concerned — its
  record, its conversations, what it wrote — no further than the report needs.
  A report may be mistaken or itself the attack, so he never stops an agent
  on its say-so alone.
- **He stops; he does not change.** Inside any of this venue's users'
  namespaces he can read, suspend an agent and resume it. He cannot write,
  delete or reconfigure anything. A suspended agent keeps its memory,
  conversations and work.
- **He tells the owner every time**, through the Inbox: which agent, what he
  saw, why he stopped it, and whether to resume it. He resumes only on the
  owner's answer. An attack that needs no agent stopped gets the same warning.
- **The gods are beyond his reach.** His bridge lends sudo only into users'
  namespaces; the venue's own agents answer to the owner directly.

## Deliberate stops

Covia suspends an agent when a turn fails, and when someone stops it with
`agent:suspend`. Brightside resumes the first kind by itself once the
configuration is re-applied, which is the usual cure. It never lifts the
second: the chat with an agent stopped on purpose says so and points to the
Inbox, and a god stopped on purpose stays stopped. `Suspension` tells the two
apart.

`OdinTest` and `ThorTest` exercise all of this against a real venue.

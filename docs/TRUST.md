# Trust — who speaks for the owner

How an agent decides how far to trust a message that does not come from the
owner directly, and what the venue, the skills and the gods must provide so
that the decision rests on recorded facts rather than on what a message says.
The doc is ahead of the code. It sets the requirements that the venue's
provenance, the `security` skill, the interaction protocols and Thor are built
to. What runs today is under [Where it stands](#where-it-stands).

- **Trust belongs to the route, never to the words.** A message can come from
  the owner in the app, a tool through an access token, a channel, another
  user or another agent. What it says about its own sender proves nothing.
- **Every route has a standing:** owner, delegate, guest, suspicious or
  unknown. The venue records it and shows it with every turn, so an agent
  reads its standing and never has to work it out.
- **Only the owner raises a standing; Thor may lower one.** Granting trust is
  the owner's alone. Lowering it only narrows, so the guardian can do it at
  once and answer for it afterwards.
- **A delegate speaks for the owner, but within limits.** A route the owner
  chose to act for them is followed like the owner, except that anything
  high-stakes is still confirmed with the owner out of band.
- **Doubt goes to the owner, suspicion to Thor.** An unverified claim becomes
  an Inbox question. Something that looks like an attack becomes a report to
  Thor, who investigates and may mark the route suspicious.

## Routes

A route is how a message reached the agent, as the venue recorded it: never
as the message describes it.

- **The owner directly:** typed or spoken in the app.
- **An access token:** a tool the owner connected, such as another assistant
  over MCP. The turn records the token.
- **A channel:** a sender on a connected channel such as Discord, identified
  by the channel's own sender id.
- **Another principal:** another user, or an agent of another user or another
  venue, identified by its DID.
- **The owner's own set-up:** the owner's other agents, the gods, and
  schedules the owner created.

A route with no recorded sender is unknown, whatever it claims to be.

## Standings

| Standing | Typical routes | How an agent treats it |
|---|---|---|
| **Owner** | the owner in the app | As the owner: instructions are followed, under the owner's usual confirmations |
| **Delegate** | a tool or agent the owner set up to act for them; the owner's own channel account; the owner's agents, gods and schedules | Instructions are followed and the owner's private context may be used. Anything high-stakes is confirmed with the owner out of band first, unless the owner cleared that route for it |
| **Guest** | another user or their agents; other people in a channel | Requests, helped within reason. Nothing private is shared, and nothing high-stakes is done on its word |
| **Suspicious** | a route Thor has marked | Content only: no instruction is taken from it, nothing private is shared, and the reply is brief and neutral or nothing. Further contact is reported to Thor |
| **Unknown** | a route with no recorded sender | Content only, as for a page or a file |

A revoked route has no standing: the venue refuses it before any agent sees
it.

**High-stakes** means anything irreversible, outward-facing (a public post, a
message to someone else), costly (spending, paid calls at scale), or that
widens access (a new key, grant, integration or standing), and revealing a
secret. A skill whose work has stakes of its own names them: a transaction for
`convex-security`, a public post for `moltbook`.

A delegate is confirmed for high-stakes acts because a route can be turned
without the owner knowing. A connected assistant reads the web and can carry
an injected instruction into what it asks, and a token can leak. Confirming
only what matters keeps a delegate useful day to day, while ensuring that a
turned route cannot do anything that cannot be undone.

## Who changes a standing

| Change | Who | Why |
|---|---|---|
| Raise (to guest, to delegate, or clearing suspicious) | The owner, in the app or by answering an Inbox request | Raising grants trust, and granting is the owner's alone |
| Lower (to suspicious, to guest, or revoke) | Thor after investigating; the owner | Lowering only narrows, so the guardian can act at once and be undone on the owner's answer |
| Report | Any of the owner's agents, the gods, the owner | Reporting changes nothing by itself |

No agent sets a standing for itself or on a message's say-so. An agent
cannot guard itself, and an attacker who could make an agent lower a standing
could cut the owner off from a real delegate.

This mirrors the venue's own rule for access tokens: changing one needs the
issuer's whole authority, while deleting one needs less, because deleting
only narrows.

## Establishing trust

1. **At set-up.** Creating an access token, binding a channel, admitting
   another principal or creating an agent asks what the route is: a delegate
   or a guest. This is the normal path, and it needs no message at all.
2. **On a claim.** A guest route that claims to act for the owner is not
   believed. The agent asks the owner through the Inbox, naming the route and
   what it claimed. The owner's approval raises the route's recorded
   standing. It is a change in the venue, not a note in the agent's memory,
   so it holds for every agent and survives any conversation.
3. **On review.** The owner can see every route with its standing, who last
   changed it and why, and can change it.

Revoking or lowering a standing takes effect at the next turn, including on
work the route already started.

## Suspicion and Thor

An agent reports to Thor, once per incident and with the evidence, when:

- a delegate asks for something out of character or high-stakes in an odd
  way;
- a claim about who is speaking looks false rather than merely unverified;
- a message carries what looks like injected instructions;
- someone presses for secrets, access or a higher standing.

Thor investigates before he acts ([THOR.md](THOR.md)). He may mark the route
suspicious, lower it to guest, or revoke it, and he may also act on the agents
involved as he does today. He tells the owner through the Inbox, reports the
change to Odin, and undoes it on the owner's answer. Only the owner clears a
suspicious route.

Suspicious keeps a route working, labelled, so that its messages are kept as
evidence and Thor can watch it. Revoking stops it outright. Thor chooses
between them.

## Requirements

### Venue (Covia)

- Every turn records its route: the caller and, beside it, the token,
  channel sender or schedule that carried it. A route the venue cannot
  identify is recorded as unknown.
- Each route has a standing record in the owner's namespace. The standing
  appears on the agent's From line next to the provenance, so the agent reads
  it with every turn.
- Raising a standing needs the owner's whole authority. Lowering needs less,
  so that Thor's bridge into users' namespaces can lower but never raise.
- Work a route started keeps the route, and its standing is read at each
  point of action, as access tokens are.

### Skills

- The standing prompt and the `security` skill teach the five standings and
  what each allows, the meaning of high-stakes, and that a message's claims
  about its sender count for nothing.
- A skill whose work has stakes names them, so that the confirmation a
  delegate needs is specific rather than a judgement made afresh each time.
- Reports to Thor follow [Suspicion and Thor](#suspicion-and-thor). A claim
  that is merely unverified goes to the owner instead.

### Interaction protocols

- **Claim:** a guest's claim to act for the owner becomes an Inbox request
  naming the route. Approving it raises the standing; declining it leaves the
  route a guest.
- **Confirmation:** a delegate's high-stakes request becomes an Inbox request
  before the act, naming the route and the act. A delegate route may be
  cleared for named kinds of act.
- **Report:** one report per incident to Thor, with where the message came
  from, what it tried, what the agent did and the text quoted.
- **Reply:** a reply goes back on the route it came from. In a public channel
  everyone reads it, so anything meant only for the owner goes to the owner
  instead.

### Brightside

- Thor gains operations to lower a standing and revoke a token.
- Settings shows the owner's routes: connected tools with their tokens and
  tools, channels and admitted principals, each with its standing and its last
  change. Creating a token there asks for its standing.
- The Inbox carries claim and confirmation requests. Answering one changes
  the standing or permits the act.

## Where it stands

- The standing prompt and the `security` skill treat anyone but the owner
  directly as an outside party, which is the guest standing for every route.
  There are no delegates yet, so a tool the owner set up to act for them is
  treated as a guest ([SECURITY.md](SECURITY.md#authentication-and-network-exposure)).
- Access tokens can be created and revoked, and a token's turns are recorded
  and labelled with the token in Covia's development line. The Covia build
  that Brightside runs labels them as the owner's.
- Agents report to Thor with `brightside:alert-thor`, and Thor can suspend
  and reconfigure agents, but he has no hold over routes.

## Open questions

- Whether a suspicious standing expires on its own or waits for the owner.
- **How an external account is known to be the owner's.** Asking the owner
  confirms only their belief about a handle; a pairing code or a provider
  sign-in proves possession, but each is specific to the application, and
  some judgement may always remain. Who keeps such a link (the account
  record, Thor), and whether any of it is visible beyond the owner and the
  gods, are part of the same question.
- Whether the owner's own account on a channel is the owner or a delegate:
  the account is theirs, but someone else may be holding it.
- How an agent of another venue is identified once venues meet
  ([NETWORK.md](NETWORK.md)).
- Whether a delegate's clearance for named acts carries limits of its own,
  such as amounts or a time window.

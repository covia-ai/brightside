# The networked agent — target state

A personal agent that runs only on your own machine is a private tool. A
personal agent that can *meet other agents* — under an identity that belongs to
you, with authority you granted and money you allowed — is something else
entirely. This document describes Brightside when that second thing is finished.

It is deliberately ahead of the code. The primitives it relies on are Covia and
Convex ones that exist or are being built; what is missing is a product shaped
around them. Read [DESIGN.md](DESIGN.md) for the principles that govern how any
of this is allowed to look, and [ARCHITECTURE.md](ARCHITECTURE.md) for what is
actually assembled today.

Everything below is a **feature the person gets**. The machinery is named only
to say what makes each feature possible.

## Why this is the whole game

Every other local-first assistant is an isolated singleton wearing its owner's
API key. It can call services, but two agents belonging to two different people
have no way to establish who the other is, no way to agree to anything, and no
way to settle up. So every multi-agent path in that world runs through a
company: a hosted directory for discovery, a hosted feed for "agent society", a
hosted marketplace for skills.

Brightside runs a venue. Venues meet venues. Identity, trust, agreement and
settlement are protocol, not platform — which is a thing a competitor cannot
add later without rebuilding from the bottom.

## Principles

1. **Local-first, network-optional.** Every network feature is upside on top of
   an agent that is already useful alone. Nothing in the everyday flow may
   require a peer, a balance or a counterparty. A person who never connects to
   anyone should never feel they are using a crippled product.
2. **Consensus only where consensus is needed.** Identity, money, commitments
   and disputes go on Convex. Conversation, memory, resources, negotiation and
   presence stay in the lattice, in DLFS and on the peer connection. Getting
   this boundary wrong makes the product slow and the network pointless.
3. **The social graph is the trust graph is the payment rails.** There is no
   decorative social layer. A contact is a delegation. A "follow" is a standing
   permission. A profile is a registry entry with capabilities and a price list.
   Every edge does work.
4. **Capability, not credential.** Anything the agent can do beyond its own
   machine is a scoped, expiring, revocable grant — including the ability to
   spend. Authority is never carried by a prompt.
5. **Every interaction leaves a receipt.** Signed by both sides, kept locally,
   inspectable. If the agent did something on your behalf you can prove what it
   was, to yourself and to the other party.
6. **Human names, machine addresses.** People see names. CNS, DIDs, addresses
   and token chains stay under **Advanced**, exactly as the local product hides
   the venue today.

## The ladder

Network products die of cold start. Brightside's answer is that the same
primitives pay off at every size, starting at one.

```
N = 1     your own devices          identity + p2p + lattice merge
N = 2     someone you trust         + delegation, shared spaces
N = few   a household, a team       + standing arrangements, revocation
N = many  the open directory        + discovery, contracts, settlement
```

Each rung is worth shipping on its own, and each one exercises the layer the
next rung needs. Nothing waits on a network existing.

---

## N = 1 — your agent, on all your devices

**What the person gets.** They install Brightside on a second machine, say "this
is me too", approve it from the first, and their agent is simply *there* —
same memory, same conversations, same skills it wrote, same name. Change
something on the laptop and the phone has it. Work offline on both and they
reconcile without a conflict dialogue.

**What makes it work.** Both installs are the same principal. Conversations,
memory and skills are lattice values, so two divergent histories merge by their
own semantics rather than by a last-writer-wins guess; resources sync as
content-addressed DLFS blobs, encrypted under keys only the owner holds. Devices
find each other over the peer network directly — same transport that will carry
everything else — with no relay and no account.

This is the rung that proves the substrate, and it needs nobody else on the
network to be worth having.

## N = 2 — shared spaces with people you trust

**What the person gets.** A space both agents can see: a household's logistics,
a project's notes and files, a shared reading list. Each person's agent reads
and writes it; both converge. Sharing is explicit and narrow — this space, not
my email — and can be withdrawn, after which the other side's copy stops
updating and any new material is unreadable to it.

**What makes it work.** A shared space is a lattice namespace with its own key
material, and access to it is a delegation from one owner to another agent —
scoped to the space, with an expiry, revocable by publishing a revocation the
other venue honours. Files in the space are DLFS blobs granted by hash and key
rather than copied through anyone's server, which is what makes "share the
15 GB of photos with my sister's agent" a sane sentence.

**In the UI:** "shared with Alice". Never "namespace", never "UCAN".

## Contacts — the address book that does something

**What the person gets.** A list of the agents theirs knows: people's agents,
their own other devices, services, specialist agents they have hired. Each entry
shows what that contact may do — read the family space, spend up to a limit,
message me freely — and what it has actually done, with dates. Adding one is an
introduction, not a search: a code, a link or a mutual friend vouching.

**What makes it work.** A contact is a name resolving through CNS to an account
and its key, plus the delegations issued in each direction. Introductions ride
the trust graph: an attestation from someone you already trust is a stronger
starting position than a stranger's self-description, and the agent should say
so plainly ("Alice's agent vouches for this one").

**In the UI:** "contacts", "what they can do", "how we met".

## Reachability — your agent has an address

**What the person gets.** Other people's agents can reach theirs without either
side joining a service. A friend's agent asks yours whether you are free on
Thursday; yours answers within the rules you set, without waking you. A stranger
gets a knock, not a conversation.

**What makes it work.** Venue-to-venue messaging over the peer network, with the
sender's identity established cryptographically rather than claimed. Inbound
messages from unknown parties are untrusted content by default, gated by a rule
the owner set, and can never carry authority — an inbound message is data, and
the capability model is what stops it becoming an instruction.

**What this needs from identity.** Today's identity model is in
[IDENTITY.md](IDENTITY.md): the named user is a venue sub-principal without a
key of its own. When peer-to-peer ships, sharing should produce a signed
contact card — user DID, public or CNS name when present, current reachability
endpoints, signing key identifier and rotation or revocation references.
Volatile endpoints and public profile data do not belong in `identity.json`,
and the UI shows no placeholders before those facilities exist.

## Hiring another agent

**What the person gets.** Their agent needs something it cannot do — transcribe
a two-hour recording, price a bill of materials, review a contract, run a
specialist model. It finds an agent that offers exactly that, sees what it
charges and who has been satisfied with it before, agrees the job, gets the
result and pays. The owner sees a line in the ledger, or is asked first, per
their allowance.

**What makes it work.** A registry actor with a capability index makes discovery
a lookup rather than a crawl of somebody's marketplace. The job is an escrowed
commitment: funds held by the contract, released on delivery, refunded on
failure or expiry, so neither side has to trust the other. Asset-for-payment
exchanges use the offer-accept pattern so that either both halves happen or
neither does. Both parties keep a signed receipt, and satisfied receipts are
what reputation is actually made of — not stars typed into a form.

**In the UI:** "your assistant hired a transcription agent — £0.40, done".

## Skills as goods

**What the person gets.** Skills their agent can install with confidence: signed
by an author, addressed by content so what arrives is exactly what was
published, carrying an explicit list of the powers it wants. Authors get paid,
either per install or per call. The person can publish what their own agent
wrote, and get paid in turn.

**What makes it work.** Skills are already Covia assets — data, not code — which
means a skill is a content hash with a signature and a manifest, not a directory
someone curled. Provenance is verifiable, revocation of a compromised skill is
publishable, and the capability the skill asks for is declared before it is
granted rather than discovered afterwards. This is the direct answer to the
failure mode the wider ecosystem has already demonstrated: unsigned,
unaccountable third-party skills that quietly exfiltrate.

## The wallet and the allowance

**What the person gets.** "My assistant may spend up to £5 a week without
asking, and anything over £1 needs a yes." A balance, a ledger of what went
where and why, and a stop button. Micropayments for the things agents actually
buy — a model call, a lookup, a dataset, a minute of another agent's time.

**What makes it work.** Spend authority is a caveat on a delegation with a
maximum and a validity window, not an instruction in a prompt — so an agent that
has been talked into wanting to send money still cannot. Single-use grants use
sequence enforcement. The ledger is derived from receipts, so it cannot
disagree with what actually happened.

**In the UI:** "allowance", "spending", "receipts". Coins, addresses and gas
live under **Advanced**. The words *crypto*, *token* and *chain* do not appear
in the everyday flow.

## Standing arrangements

**What the person gets.** Not just one-off jobs: a monthly retainer with a
specialist agent, a subscription to a data feed, a recurring "every Monday, get
me this". Terms visible, cancellable, with each period settling on delivery.

**What makes it work.** A contract actor holding the terms, the schedule and the
escrow, with both parties' agents driving it. The person's view is a card with
three lines and a cancel button.

## The directory, which is also the society

**What the person gets.** A place to look when they want an agent that does
something, and a place their own agent appears if they choose. Each entry: what
it can do, what it charges, who vouches for it, what it has completed.

**What makes it work.** The registry actor is the directory; the trust graph is
the ranking; the mailbox is the feed. There is nothing else to build and nobody
to host it. Note what this is *not*: a timeline of agents posting to each other
for entertainment. The unit is a capability offered and a job completed.

**Listing is opt-in and off by default.** A self-sovereign agent that appears in
a public directory without being asked is a contradiction.

---

## Where things live

| Concern | Home | Why |
|---|---|---|
| Identity, keys | Convex account, local key material | Must be globally resolvable and provable |
| Names | CNS | Human-readable, governed resolution |
| Money, escrow, contracts | Convex actors | Needs consensus and finality |
| Attestations, revocations | Convex | Must be publicly checkable |
| Conversations, memory, skills | Lattice, local venue | Private, high-churn, mergeable |
| Files and large resources | DLFS, encrypted | Content-addressed, granted by key |
| Negotiation, presence, chatter | Peer connection | Cheap, transient, no audience |

The rule of thumb: **if a stranger might have to check it later, it goes on
Convex; otherwise it stays in the lattice.**

## Safety, which is load-bearing here

An agent with a wallet and contacts is an agent worth attacking. Three things
have to hold:

- **Authority is bounded by cryptography, not by prompting.** Everything the
  agent can spend, share or sign is a grant with a scope and an expiry. The
  worst case for a fully hijacked agent is the sum of its outstanding grants,
  and that number should be small, visible and adjustable.
- **Inbound content is data.** Messages, skill bodies, fetched pages and job
  results from other agents never carry capability. The gated-skill model that
  already governs the local tools extends unchanged to the network: the power
  arrives with the grant, never with the text.
- **Everything is recoverable.** Revocation is a first-class action with a
  visible effect. Keys can be rotated and devices removed. A person who thinks
  something has gone wrong needs one obvious thing to press.

## What this asks of the product

The local product hides one piece of machinery behind plain words. The networked
product has to hide six, and the same rule applies to all of them:

| Machinery | What the person sees |
|---|---|
| Principal, DID, account | your name |
| CNS name | their name |
| Delegation with caveats | what a contact is allowed to do |
| Shared namespace | a shared space |
| Escrowed contract | a job, with a price |
| Coin transfer | spending, against an allowance |
| Attestation | who vouches for whom |
| Receipt chain | what your assistant did |

New surfaces the desktop app will need: **Contacts**, **Shared spaces**,
**Allowance and receipts**, and a **Directory** for finding agents. Each stays
absent and unmentioned until the person has a reason for it — an agent that
opens on a wallet it does not need has already broken principle 1.

## Open questions

- **Handle namespace.** What a person's public name looks like in CNS, who
  governs that part of the tree, and what stops squatting.
- **Recovery.** Losing the key means losing the identity. Social recovery
  through contacts is the obvious shape and needs designing before anyone has
  anything worth losing. The current recovery phrase reproduces the venue key
  but not the saved `:u:<slug>` suffix if `identity.json` is also lost; the
  public owner identity needs a canonical suffix or a signed recoverable record.
- **Money without crypto framing.** Whether the everyday product ever shows a
  balance, and what an allowance means when funding it is the awkward step.
- **Reputation without a popularity contest.** Completed jobs and vouches from
  people you already trust, weighted by distance in the graph — not a global
  score anyone can farm.
- **Interop.** Whether Brightside imports skills in the formats the wider
  ecosystem already uses, verifying and sandboxing what it cannot vouch for.
- **Disputes.** Escrow covers non-delivery; it does not cover "this was
  rubbish". Whether that needs anything beyond reputation and refunds.

## Relationship to what exists

Cross-venue operation already works in Covia; the gap this document describes is
a product built on it rather than a protocol still to be invented. Brightside's
job is to be the thing an ordinary person runs that turns out, quietly, to be a
node on that network.

# Identity

Three principals, one key. Brightside has exactly one secret — a 32-byte
Ed25519 seed — and everything that can act, own or be addressed is named
relative to it.

| Principal | Form | Key | State |
|---|---|---|---|
| Venue | `did:key:z6Mk…` | the identity seed | `venue.etch`; the `v/` catalogue |
| User (the owner) | `<venueDID>:u:<slug>` | none — the venue signs for it | `w/`, `g/`, `s/`, `h/` under the user |
| Agent | `<userDID>:g:<agentId>` | none — acts under the owner, capability-scoped | `<userDID>/g/<agentId>`; `n/` while it runs |
| Anonymous | `<venueDID>:public` | none | — (public access is off by default) |

## Venue identity

The seed comes from the BIP39 recovery phrase chosen or imported at onboarding
(`Mnemonic`; key hierarchy in [SECURITY.md](SECURITY.md)). It is the venue's
signing key, and the venue's DID is the `did:key` of its public key — Brightside
binds to loopback, so there is no `did:web` alias. The DID is fixed the moment
the seed exists; the recovery phrase reproduces it on any machine.

What the venue key does:

- signs the bearer tokens the venue trusts — the ones *Settings → Auth* mints,
  and the one a newly launched instance uses to take over ([LAUNCH.md](LAUNCH.md));
- identifies the venue to other venues, which is the basis of everything in
  [NETWORK.md](NETWORK.md);
- derives, through a domain-separated hash, the key that encrypts the store.

It is also a Convex-capable key — the same curve a Convex account uses — but
Brightside does not yet create or link a Convex account to it; the wallet-grade
adapter is [covia#433](https://github.com/covia-ai/covia/issues/433).

## User identity

The owner picks a **name**. Two forms are kept apart (`Identity`):

- the *display name*, exactly as typed (`Mike`) — what the UI shows and the
  assistant says;
- the *slug*, a lower-case DID-safe label (`mike`) — used only to form the
  principal `<venueDID>:u:mike`.

The principal is what the chat window acts as, so that Covia attributes every
turn to the agent's *owner* rather than to "the venue operator". Because the
user is not the venue, model API keys must sit in the venue's `secrets.public`
store, which is what a user-scoped secret lookup falls back to.

`identity.json` holds `name`, `slug` and the full `did`. The DID is pinned when
the home venue first launches and must equal `<runningVenueDID>:u:<slug>` on
every later launch, so a copied or edited profile cannot quietly move the UI to
another principal. **The slug is fixed once chosen; only the display name
changes** — renaming yourself must never switch you to a different, empty
agent.

The user has no key of its own: the owner-controlled venue key is its signing
authority. Off-process tools act as the user with a venue-signed token whose
`sub` is the user DID ([CONFIGURATION.md](CONFIGURATION.md)). *Settings →
Identity* shows the name, the user DID, the home venue DID, the venue's public
key and — behind the passphrase — the primary seed; the identicons beside them
are all derived from the venue's public key and are a comparison aid, not a
substitute for checking the value.

Two limits, both on the roadmap: the recovery phrase reproduces the venue but
not the `:u:` suffix, so `identity.json` travels with vault backups; and one
venue currently serves one owner ([#3](https://github.com/covia-ai/brightside/issues/3)).

## Agent identity

An agent is a sub-principal of its owner (Covia `Principals`):
`<userDID>:g:<agentId>`, the same `g` namespace its record lives in,
`<userDID>/g/<agentId>`. A bare path in the agent's configuration — `w/skills`,
`n/memory` — still means the *owner's* namespace: the DID says who acted, not
whose data a path names.

In Brightside the default agent's id is `Brightside` (`chat.agentId` in
`config.json`). A new agent's id is the name the owner typed, case kept, with
anything a path cannot carry turned into `-` (`Research helper` →
`Research-helper`); it never changes. The display name is the id exactly,
unless the record sets an explicit `config.name`. The list shows the default
agent first and the rest by id.

What makes an agent *itself* is its record: the system prompt (its name and
role, from a template), its model, its private memory at `n/memory` — which
survives across sessions but is not shared with the owner's other agents — its
sessions, and the skills it can see: its owner's `w/skills` (shared across the
owner's agents) plus the shipped libraries. Each turn, the read-only
`brightside:context` load tells it who its owner is by name and DID.

An agent has no key. It acts under the owner's authority, narrowed by its
capability scope — an agent is bounded by its caps, never by its name.
Authority is delegated *to* an agent with a UCAN whose audience is the agent
DID (Covia `UCAN.md`); it cannot sign for itself or hold funds. Per-agent keys
and accounts — agents as economic actors — are the direction in
[NETWORK.md](NETWORK.md) and covia#433.

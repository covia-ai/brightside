# Identity

Three principals, one key. Brightside has exactly one secret, a 32-byte
Ed25519 seed, and everything that can act, own or be addressed is named
relative to it.

- **The venue** is the seed's `did:key`: it signs tokens, owns the store's
  encryption key, and is what other venues will know Brightside by.
- **The owner** is a user of that venue, `<venueDID>:u:<slug>`, with no key of
  its own: the venue signs for it, and the chat acts as it so every turn is
  attributed to the owner rather than to "the operator".
- **An agent** is a sub-principal of its owner, `<userDID>:g:<agentId>`,
  bounded by its capabilities, never by its name.
- **The slug is fixed once chosen.** Renaming yourself changes the display
  name only; it never switches you to a different, empty agent.

| Principal | Form | Key | State |
|---|---|---|---|
| Venue | `did:key:z6Mk…` | the identity seed | `venue.etch`; the `v/` catalogue |
| User (the owner) | `<venueDID>:u:<slug>` | none — the venue signs for it | `w/`, `g/`, `s/`, `h/` under the user |
| Agent | `<userDID>:g:<agentId>` | none — acts under the owner, capability-scoped | `<userDID>/g/<agentId>`; `n/` while it runs |
| Anonymous | `<venueDID>:public` | none | none; public access is off by default |

## Venue identity

The seed comes from the BIP39 recovery phrase chosen or imported at onboarding
(key hierarchy in [SECURITY.md](SECURITY.md)). The venue's DID is the
`did:key` of its public key, fixed the moment the seed exists and reproduced by
the phrase on any machine; Brightside binds to loopback, so there is no
`did:web` alias. The venue key signs the bearer tokens the venue trusts,
identifies the venue to other venues ([NETWORK.md](NETWORK.md)), and derives
the key that encrypts the store. It is Convex-capable, but Brightside does not
yet create or link a Convex account to it.

## User identity

The owner picks a **name**. `Identity` keeps two forms apart: the display
name exactly as typed, which the UI shows and the assistant says; and the
slug, a lower-case DID-safe label used only to form the principal.
`identity.json` holds name, slug and the full DID. The DID is pinned when the
home venue first launches and must equal `<runningVenueDID>:u:<slug>` on every
later launch, so a copied or edited profile cannot quietly move the UI to
another principal.

The user has no key: the owner-controlled venue key is its signing authority.
Off-process tools act as the user with a venue-signed token whose subject is
the user DID ([CONFIGURATION.md](CONFIGURATION.md)). Model API keys are kept
per user in the venue's encrypted secret stores, so each user's agents resolve
that user's key. *Settings → Identity* shows the name, the user DID, the home
venue DID, the venue's public key and, behind the passphrase, the seed; the
identicons beside them derive from the venue's public key and are a comparison
aid, not a substitute for checking the value.

**Switching user.** *Settings → Identity → Switch user* switches the app to
the venue principal itself for the session: the agents pane lists the venue's
own agents with [Odin](ODIN.md) first, the chat talks to them and the Inbox is
the venue's. The operator has a display name of its own, kept in
`identity.json` as `operator`; it is a label only, since the operator is the
venue itself and has no slug or user DID. Every launch starts as the user.

## Agent identity

An agent is `<userDID>:g:<agentId>`, the same `g` namespace its record lives
in. A bare path in its configuration, `w/skills` or `n/memory`, still means
the owner's namespace: the DID says who acted, not whose data a path names.

The default agent is `Brightside` (`chat.agentId`). A new agent's id is the
name the owner typed, with anything a path cannot carry turned into `-`, and it
never changes; the display name is the id unless the record sets one. What
makes an agent itself is its record: its system prompt, its model, its private
memory at `n/memory` (across sessions, not shared with the owner's other
agents), its sessions, and the skills it can see. Each turn, the read-only
`brightside:context` load tells it who its owner is.

An agent has no key and cannot sign for itself or hold funds. Authority is
delegated to it with a UCAN whose audience is the agent DID. Per-agent keys and
accounts, agents as economic actors, are the direction in
[NETWORK.md](NETWORK.md).

## Known limits

- The recovery phrase reproduces the venue but not the `:u:` suffix, so
  `identity.json` travels with vault backups.
- One venue serves one owner
  ([brightside#3](https://github.com/covia-ai/brightside/issues/3)).
- A `did:key` venue cannot yet mint UCAN grants for its own users, so an Inbox
  answer that offers a grant fails until
  [covia#440](https://github.com/covia-ai/covia/issues/440) lands; answers
  without a grant are unaffected.

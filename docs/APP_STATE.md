# Brightside's own state as a lattice application — proposal

A proposal, for discussion before it is built: Brightside keeps everything of
its own that no other region owns in one lattice region, `[:app :brightside]`,
managed by a Brightside lattice application and its domain components. Covia
keeps conversations, agents, skills and secrets in its regions, P2P keeps
nodes and identities in its, DLFS keeps files in its; this region holds the
rest. The component model is Convex's (`convex-core/docs/LATTICE_APPLICATIONS.md`
in the Convex repository); the peer map that motivated it is [PEERS.md](PEERS.md).

- **One region, owned per person.** Non-core applications don't get a
  top-level region of their own: they live under `:app`, keyed by application
  name, beside the core regions (`:grid`, `:p2p`, `:fs`). Brightside's is
  `[:app :brightside]`, keyed by the person's DID and signed by the venue on
  their behalf, as the venue signs for its users today.
- **Typed components, not blobs.** A `BrightsideApplication` hosts a
  component per person, and that hands out smaller ones (profile, model,
  peers map, integrations, devices) with domain methods. Nothing writes a
  whole value when it changes one part.
- **Merge chosen per part.** Each leaf merges in the way that suits it, so two
  of your devices changing different things never lose either change.
- **Inside the store, so inside recovery.** What moves in is encrypted with
  the store, backed up with it, and reproduced by the recovery phrase.
- **Only what can wait for the unlock.** Keys, the vault and anything needed
  to open the store stay outside; so does a small cache that lets the unlock
  screen look right before the store opens.

## What it holds

| State | Today | In the region |
|---|---|---|
| Display name, operator label | `identity.json` | `:profile`, per field |
| Slug and user DID | `identity.json` | `:profile`; recovery then reproduces them, closing the gap in [IDENTITY.md](IDENTITY.md)'s known limits |
| Chosen model | `model.txt` | `:model` |
| Theme, mode, accent | `prefs.properties` | `:look` for the person; a device may override in `:devices` |
| Tray behaviour | `prefs.properties` | `:devices`, since it belongs to one desktop |
| Peers map settings | adapter state | `:peers` (orbits, placements by DID) |
| Moltbook claim page | `w/moltbook` in the person's workspace | `:integrations` |
| Requests already notified | memory only | `:seen`, so a restart does not notify twice |
| Last tab, window size, the map's zoom, unsent drafts | memory only | `:devices`, per device |
| Migrations applied | implied by code | `:version` |

It holds nothing another region already owns: conversations, agents and their
memory, skills, secrets and scheduled work are Covia's; nodes and identities on
the network are P2P's; files are DLFS's. The skill-feedback backlog stays in
the person's Covia workspace, because the assistant reads and writes it there
with its ordinary tools.

**Staying outside.** The vault files (`identity.enc`, `vault.salt`,
`keys.enc`, `unlock.passphrase`) unlock the store, so they cannot be in it.
`config.json` is the operator's hand-edited configuration and names the store
itself, so it stays a file. Logs, `files/`, `code/` and `skills/` are the
owner's files on disk. Two things are read before the unlock today, the look
and the name to greet: the application writes them to a small
`prefs.properties` cache whenever they change, and the cache is never the
source of truth.

## Shape

```
:app → KeyedLattice, application name → that application's lattice
  :brightside → OwnerLattice, keyed by the person's DID, venue-signed
    :profile       → KeyedLattice of LWW fields
    :model         → LWW
    :look          → KeyedLattice of LWW fields
    :peers
        :orbits      → LWW (the list changes rarely, and as a whole)
        :placements  → MapLattice, DID → LWW placement
    :integrations  → MapLattice, name → LWW record
    :seen          → SetLattice of request ids (grows; pruned by the application)
    :devices       → MapLattice, device id → KeyedLattice of LWW fields
    :version       → MaxLattice
```

`:app` composes like the root does, one level down: a node registers the
applications it hosts, and the keyed merge ignores an application it does not
know arriving from a peer while retaining one it already holds. So a venue
without Brightside carries nothing of it, and adding an application never
needs a new top-level region.

Every last-writer-wins leaf carries its own timestamp, written by a stamped
cursor at that leaf, so merging two devices' values settles each leaf on its
own. Placements merging per DID is what lets the laptop move Alice while the
phone moves Sam. Device state is keyed by a device id generated on first run,
so it travels with the person but each device reads only its own; a device
that is gone is pruned by the person, never automatically.

The components are the only way in. `PersonState` has `profile()`,
`model()`, `look()`, `peers()`, `integrations()`, `seen()` and `device(id)`;
`PeersMap` has place, resize an orbit and reset; each is a component over its
own cursor, nested under the person's, so the region's policy (owner, signing,
stamping) is set once where the components are built. The map settings value
in `brightside.peers` becomes what `PeersMap` reads and writes, rather than a
blob it stores.

## Hosting and replication

The application attaches to the venue's root host like Covia's own
application, and is constructed in the venue's start-up hook, before anything
reads it. Until lattice subscriptions exist upstream, screens notice changes
by comparing immutable values, as the inbox and sessions already do.

The region replicates only to the person's own devices. A venue serving other
people, or a P2P peer, has no reason to receive it, and a node ignores regions
it does not recognise; the propagation filter should still exclude it by
default rather than rely on that.

## Migration

On the first launch with the application, each value is read once from where
it lives today (`identity.json`, `model.txt`, `prefs.properties`,
`w/moltbook`, the adapter state) and written into the region; the old
locations are then read no more. `identity.json` stays as the pre-unlock
cache and a recovery aid until the region is trusted, and is retired after.

## What this asks of Covia and Convex

- **An `:app` region, and a way into it.** CAD045 ("Registration") puts
  application-specific lattices under `:app`, keyed by application name, and
  keeps root regions for ecosystem facilities allocated by the Convex
  Foundation. Convex's root lattice does not register `:app` yet. Covia then
  has to let an embedder register its application under `:app` before launch
  and get a cursor or host for it; the start-up hook is the natural place.
- **Owners that are DIDs.** CAD045's owner lattice partitions by owner
  public key, and the people on a venue have none: the venue signs for them.
  Convex's application guide allows an indirect owner, a DID resolved by the
  installed owner verifier, so the venue's verifier has to resolve its own
  users' DIDs to the venue key for this region.
- **The component model as it settles.** Components, stamped and signed
  cursors and publication are being reworked on Convex's lattice-application
  branch; this is best built on the release that carries it, and prototyped
  against it first.
- **Propagation filters by region**, so the person's devices sync this region
  and nobody else is sent it.

## Steps

1. **A standalone prototype**: the lattice, the application and the
   components, on a `RootComponent` over a memory store, with tests of merge
   (two values edited apart, merged, each change kept), signing and the
   domain methods. Needs nothing from Covia.
2. **Register `:app`** in Convex's root lattice, and ask Covia to let an
   embedder register under it.
3. **Host it in the venue** and move the peers map onto it.
4. **Migrate** the rest, one part at a time.
5. **Sync between your devices** once P2P is in the venue ([covia#587](https://github.com/covia-ai/covia/issues/587)).

## Open decisions

- Whether `:look` belongs to the person, with devices overriding, or to each
  device alone.
- How long `identity.json` stays once the region holds the profile.

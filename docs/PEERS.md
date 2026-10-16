# Peers — from a preview to live nodes

How the Peers tab gets from sample data to the real network: what Brightside
holds about other nodes, where that comes from, and how the map's own settings
are kept apart from it. The model is `brightside.peers`; the map and its
screen are `ui/peers`, drawing a sample source today. Past the first step the
doc is ahead of the code. The networked product it serves is in
[NETWORK.md](NETWORK.md).

- **Two structures, never mixed.** *Peer data* is what is known about other
  nodes, as a source reports it. *Map settings* are how this installation
  chooses to draw them: orbit sizes, where a body was dragged, the view.
  Settings refer to nodes by DID and hold no facts about them.
- **Assume little about a node.** A node is a DID, a display name, what it is,
  how it relates to you, its status, what it belongs to, and an open set of
  facts. Everything else is expected to change as the network's records do.
- **One source, many kinds.** The map reads peer data from a source: the
  samples today, then local services, then the P2P network. Which orbit a node
  sits on is a setting over its relation, not code.
- **Settings are local and disposable.** They live with this installation,
  never travel to peers, and can be reset at any time without touching
  anything real.
- **Live first where it is honest.** The services Brightside already talks to
  can be shown live before any P2P exists.

## Peer data

A node is whatever the network says another participant is: a venue, an agent
on a venue, one of your own devices, a service. Brightside assumes only this
much:

| Part | What it is |
|---|---|
| Identity | The node's DID. The one stable key; everything else hangs off it. |
| Name | A real, human name for the node, as it gives it or as you know it. |
| Kind | Venue, agent, device or service; open-ended, an unknown kind is shown plainly. |
| Relation | How it relates to you: yourself, someone you trust, something you use, known but not connected; and, for venues drawn far out, linked to you, listed in a directory, or unknown. |
| Status | Connected, idle, offline, or not connected. |
| Belongs to | The DID of the node this one is part of. An agent belonging to a planet is its moon; a planet belonging to a distant venue is that venue's place among your peers. |
| Facts | An ordered set of labelled values for the card: address, reach, how you met, what they may do, what is shared. |

Facts are an open list, not fields, so a new kind of record needs no change
to the structure: the card shows a plain fact as a row and a list fact, such
as what a node may do, as a section. Peer data is read-only to the map.
Dragging, zooming or resizing an orbit never writes to it.

A node whose relation has an orbit is a planet. A node without one is a moon
if it belongs to a planet, and otherwise a star system far out, linked to you
when its relation is "linked".

## Where peer data comes from

The map reads one source, which can be swapped or combined, and refreshes
when the source says something changed:

1. **Samples**, as today. They stay as a demo mode for screenshots once live
   sources exist. The "Preview" chip goes when a live source is in use.
2. **Local services.** Brightside already talks to real services: the model
   provider, Discord, Moltbook, the Convex peer, Claude Code, and MCP clients
   connected to the venue. Each can be a node on the services orbit with its
   live status. This needs nothing upstream.
3. **The P2P network.** Venues and agents discovered and connected through a
   node run inside the venue, with real DIDs and names.

The relation, and so the orbit, comes from what the venue already knows about
the node, not from the map. Nodes under your own identity are your devices.
Nodes you hold capability grants with, or follow, are people you trust. Nodes
running work for you are services. Nodes vouched for, or found in a directory,
are known but not connected. Everything else is unknown, and is drawn
unlabelled far out.

## Map settings

Map settings are how this installation draws the peer data. They live in a
structure of their own:

- **Orbits:** each one's name, the relations it holds, and its radius.
  Dragging an orbit's line resizes it, kept between its neighbours; adding an
  orbit, or moving a relation to another, is a settings change too.
- **Placements**, keyed by DID: a planet's orbit radius and angle where it was
  dropped, or a distant venue's position. A node with no placement is placed
  from a hash of its DID, so the same node lands in the same place on every
  launch without anything being stored; unplaced venues are then pushed apart
  as they are drawn, so none sit on top of another.
- **View:** the zoom to open on, and whether the map is paused.
- **A version**, so the structure can evolve. Anything stored that does not
  make sense, from another version or none, falls back to the defaults.

Settings never hold a node's name, status or facts. A placement for a DID the
source no longer reports is pruned when the tab opens; resetting clears
placements and orbit sizes and nothing else. The map's clock is the wall
clock, so planets keep moving while Brightside is closed and placements hold
across launches.

**Where they live.** In Brightside's own adapter workspace on the venue, under
the acting person's DID: venue-private, encrypted with the store, and outside
the person's own namespace, so no other principal reads them through it. The
app reads and writes them in-process, a moment after a change settles, so
arranging the map leaves no job records (`MapSettingsStoreTest`). Acting as
the operator keeps a separate arrangement.

## Steps

1. **A model behind the map** (in place). The map reads peer data from a
   source and map settings from their own store, joined by DID; the samples
   are one source (`SamplePeerSource`). `MapSettingsTest` covers the relation
   to orbit, the hashed defaults, resizing and the stored form.
2. **Local services live.** The services orbit shows the real connections
   listed above, with real status.
3. **A P2P node in the venue (Covia).** Discovery and connection, under the
   venue's identity, off by default and joined from Settings, with a
   read-only peers operation and change events. Brightside reads it through
   the embedded API, as it reads everything else.
4. **Relations.** Grants and follows place people on the inner orbits;
   introductions arrive through people you trust.
5. **Agents and traffic.** Moons from the agents a peer lets you see; packets
   from real remote jobs and messages.
6. **Actions.** The card's Message and Manage access become real.

## Open decisions

- **Joining the network:** opt-in and off by default is the recommendation;
  before joining, the map shows you and your services.
- **The P2P key:** the venue key is simplest; a separate key rotates more
  easily ([brightside#5](https://github.com/covia-ai/brightside/issues/5)).
- **Strangers:** a network may hold far more nodes than are worth drawing;
  show those actually seen, grouped by region, unlabelled until pointed at.
- **Your devices:** that orbit stays empty until Brightside runs on more than
  one device.

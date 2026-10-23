# Advanced memory — proposals for consideration

Quick suggestions for growing the assistant's memory beyond one pinned list,
for discussion before any of it is built. Nothing here is implemented; the
current design is in [SKILLS.md](SKILLS.md). The ideas are distilled from
OpenClaw's memory architecture (`docs/concepts/memory-architecture.md` in the
sibling `openclaw` checkout), reshaped for Covia's principals, lattice and
scheduler.

- **Keep the pin, gate the writes.** The pinned memory stays trusted
  instruction, as it is today; the boundary moves to what is allowed in.
- **Split curated from episodic.** A small always-pinned core, and a large
  searchable tier that is never injected unasked.
- **One writer for the core.** A scheduled consolidation pass promotes what
  kept being useful; the assistant's own edits stay possible but rarer.
- **Recall without a model call.** Ranking and trigger phrases do the
  everyday work; a recall sub-agent is the exception, not the rule.
- **Remembering to act is not remembering facts.** Intentions compile into
  the scheduler; they do not sit in prose waiting to be noticed.
- **Everything the owner can see and undo.** Promotions are reviewable,
  provenance is visible, and forgetting by source is one action.

## Today

Each assistant has two numbered lists of facts, edited with Covia's memory
tool and rendered into every turn through the read-only recall operation as
watched pins: `w/memory`, what the owner is like and prefers, shared by all
their assistants, and `n/memory`, the assistant's own. Odin keeps his at the
venue's `n/memory`. Past
conversations are readable as evidence through the session tools, not
searched. Nothing records where a fact came from, nothing consolidates, and
nothing expires.

## Tiers

- **Instructions** — the system prompt, `brightside-context` and loaded skills.
  Owner- and venue-written; never touched by memory machinery.
- **Curated core** — the pinned list, kept small and budgeted. Written by
  consolidation and by explicit owner requests ("remember that…").
- **Episodic** — dated notes the assistant leaves as it works, plus past
  session transcripts. Large, append-friendly, reachable only through a
  search operation or an explicit read. Never pinned.
- **Prospective** — scheduled requests and event-triggered intents. Injected
  only when they fire.
- **Review** — a log of what consolidation promoted, changed or dropped, for
  the owner.

The line that matters is curated versus episodic: nothing crosses it without
the gates below.

## Provenance at the write path

- Store each item as a record, not a bare string: `text`, `origin`,
  `observed`, `source` (session, job or skill), optional `importance` and
  `triggers`. The memory operation's `recall` already renders a map of records
  by a display field, so the pin need not change shape.
- Origin is a closed set stamped by the operation from the request context,
  never from the text: `owner` (the owner said it in chat or the Inbox),
  `agent` (derived from the owner's material), `untrusted` (derived from web
  pages, tool results, other principals' messages, Discord strangers),
  `system` (scheduler preambles, automation prompts). Unknown defaults
  to `untrusted`, never to `owner`.
- Taint within a turn: once an HTTP, Discord or file read has returned, the
  rest of that cycle's writes are `untrusted` until the owner speaks again.
  Covia's cycle record already knows which tools ran.
- Session-kind gating: cycles started by the scheduler, by another agent's
  request or by a sub-goal may write episodic notes but never candidates for
  the core.
- Recall-loop prevention: anything rendered from memory into context is
  marked, so a recalled fact is never re-remembered as a new one.
- The pin remains `trusted`; provenance decides promotion and deletion, not
  how the model reads what is pinned. Covia's capability checks stay the
  boundary between principals ([SECURITY.md](SECURITY.md)).

## Consolidation

- A scheduled agent request to the assistant itself, nightly by default, on
  Covia's interval scheduler; the `automation` skill already teaches the
  self-rescheduling pattern. Off for agents that never write episodic notes.
- Two gates in sequence. First a deterministic one: candidates ranked by how
  often they were recalled, over how many distinct queries and days, and how
  recently; `untrusted` and `system` origins are excluded before any prompt
  is built. Then a bounded model turn that takes the gated candidates plus the
  current core and returns a revised core: duplicates merged, superseded items
  retired in place, each item anchored to its episodic source.
- Accept the rewrite only if it validates, fits the pin's budget and does not
  drop more than a bounded share of existing items; otherwise append only.
  Lattice writes are atomic compare-and-swap, so a concurrent edit by the
  assistant or the owner simply makes that sweep retry or fall back.
- Keep the pre-image and append a one-paragraph account of the sweep to the
  review log. No promotion should be a surprise.
- The same pass is the natural home for expiry: items with a review date, or
  not recalled for a long time, are proposed for removal rather than silently
  dropped.

## Recall

- **Always on, no model call.** The pin, refreshed by the watched observation
  when it changes. A search operation over the episodic tier scoring keyword
  match, an optional embedding, recency decay and write-time importance. A
  trigger prefilter that matches each inbound message against items'
  `triggers` and injects at most a few core items as a compact block.
- Only the curated tier can auto-inject. Episodic material arrives solely
  through an explicit search or read, wrapped as a tool result, so unvetted
  text never rides into the prompt on an ordinary turn.
- **Escalation.** A recall sub-goal that searches past sessions runs only when
  the message shows recall intent ("last week", "what did we decide") and the
  cheap lane found nothing strong. The goal-tree harness already gives it an
  isolated frame.
- Embeddings are optional and off unless a local model is configured, so
  memory text does not leave the machine by default; keyword scoring alone is
  the baseline.

## The owner model

- A separate small list of directives about the owner — "prefer British
  spelling", "never schedule before nine" — alongside the facts list, because
  preference adherence and fact recall fail differently.
- Entries are imperative, dated and superseded in place; an append-only
  history of preferences makes the model answer from the stale one.
- `brightside-context` keeps name, DID and model route; the directives list is
  memory and stays editable by the owner from Settings.

## Remembering to act

- A time-bound intent ("remind me Friday") becomes a scheduled agent request
  the moment it is said, with a self-contained input and the session id to
  report into.
- An event-bound intent ("when the build fails, mention the workaround")
  becomes a small record with trigger keywords, scope (channel, sender),
  expiry, fire budget and cooldown, matched by a deterministic prefilter on
  each inbound message and injected as context when it fires.
- Anti-nagging is structural: default cooldown, a small fire budget, a
  default expiry, and a cap on intents injected per turn.
- Aspirations that cannot be compiled stay as memory items with a review date
  for the consolidation pass to expire or escalate.

## Compaction

- Before the assistant compacts a long conversation, a silent turn asks it to
  write unwritten durable facts to the episodic tier, so the summary can never
  be the only copy. The compaction rebuild already refreshes the pin.
- The compaction summary itself stays the assistant's; it is never promoted
  to the core unpromoted.

## Owner surfaces

- A memory view under Settings: the core with provenance, the directives, the
  review log, and a search box over the episodic tier.
- Forget by source: remove every item derived from a chosen session, skill or
  channel in one action, and exclude that source from future consolidation.
- Promotions that touch anything sensitive, or that consolidation is unsure
  about, can go to the Inbox as a request rather than be applied.
- Odin's memory follows the same rules with the venue as owner.

## Costs to weigh

- Consolidation and escalation are background model calls; the budget for
  them is a product decision, surfaced in Settings, not an always-on default.
- Provenance is only as good as declaration coverage: a tool that does not
  say its result is external cannot taint the turn.
- Two lists and a search index are more for the owner to understand than one
  list; the everyday screens must stay as plain as they are now.

## Known limits

- Searchable, bounded memory in Covia is
  [covia#429](https://github.com/covia-ai/covia/issues/429); the pin and
  memory tool exist, the search operation and index do not.
- Confirmation of destructive memory operations rides on
  [brightside#16](https://github.com/covia-ai/brightside/issues/16).

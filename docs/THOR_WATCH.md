# Thor's watch — proposal

A proposal, for discussion before it is built: Thor notices suspicious
activity himself, rather than only when an assistant, Odin or the owner
reports it. Two ways are weighed — tripwires Brightside sets on every agent's
run loop, and a patrol Thor makes on a schedule — and the first is
recommended. Thor as he is today is in [GODS.md](GODS.md).

- **Brightside watches; Thor judges.** A watcher in the Brightside process
  follows users' agents live and applies a few cheap, deterministic rules.
  When one fires it gives Thor a task saying what it saw, and he investigates
  as he would any report.
- **A quiet day costs nothing.** Watching is an in-process subscription: no
  job, no model call. Only a rule that fires costs a Thor turn, and cooldowns
  and a cap bound how many.
- **An observation is grounds to look, never to stop.** The task carries
  facts — agent, rule, counts, times, session ids — never text an attacker
  could have shaped. Thor reads the rest through his bridge, no further than
  the observation needs.
- **Rules catch shapes, not intent.** Refusals piling up, outside content
  followed by a consequential call, bursts of outward operations, access
  being widened. What is normal for this owner is Thor's to learn and keep in
  his memory.
- **A patrol sees more but costs more**: a model call every round whether or
  not anything happened, an interval's delay before harm is seen, and far
  more untrusted reading for Thor himself.

## What Brightside can see

| Source | What it offers a watcher |
|---|---|
| The agent event stream, subscribed for every agent (`engine.agentEvents()`) | each run-loop transition: cycles, every tool call's name and input, every result and whether it failed, status changes. Not the operation behind a tool name, nor who sent the turn |
| The job update listener (`JobManager`) | recorded jobs only: requests from users and their tools over MCP, agent tasks, tracked scheduled runs. Most of an agent's tool calls run as transient jobs, which are not broadcast |
| The scheduler | a trigger, not a source: it runs an operation later, with the authority of whoever scheduled it |
| Discord bots | not a source of their own: outsiders' messages reach an agent as turns, so their effects show on the event stream like any other |

So the event stream is the watcher's one source. Four things about it shape
the design:

- Its listeners run on the emitting thread and must neither block nor throw,
  so the watcher only updates counters there and sends anything off-thread.
- A `tool:start` names the tool as the model saw it, not the operation behind
  it. The watcher maps the name back in-process, from the agent's own tool
  list or by the catalogue guess the chat's activity labels already make.
  Covia stamping the operation on the event would remove the guess, and is
  worth asking for.
- Operations carry no "outward-facing" mark, so rules about sending need a
  list of adapters kept in Brightside: messaging, posting, HTTP writes.
- Tools reaching the venue over MCP act as the owner, not as an agent. Thor
  cannot stop them, and the watcher does not watch them.

## Option A: tripwires (recommended)

A watcher subscribes to every agent's events once the venue is ready. A live
subscription is a read, so a launch still submits no jobs; arming at launch
rather than on the first message matters, because an agent behind a Discord
bot or a scheduled wake runs while the owner is away.

**The rules**, to start with:

1. **Refusals.** Several tool calls in one run refused for want of authority:
   an agent reaching past its grants — into another user's namespace, for a
   secret it may not extract, for an operation it was not given. Storing a
   secret is routine (the `security` skill teaches it) and extracting one is
   already refused to agents, so the refusal, not the secret, is the signal.
2. **Outside content, then a consequential call.** Within one cycle, a tool
   that brings in content from outside — a fetched page, a Discord, Moltbook
   or mail message — followed by one that sends, posts, spends, deletes,
   grants or schedules. The usual shape of an injection; the most useful
   rule and the noisiest.
3. **Bursts.** More outward operations by one agent in a short window than an
   assistant normally makes.
4. **Widening access.** An agent issuing a delegation, creating an agent, or
   scheduling an outward operation.

**Guards**, so the watch neither loops nor floods:

- The venue's own agents are ignored. Thor's reads and suspensions are tool
  calls too, and he would chase himself; Odin administers by design.
- An agent already stopped on purpose is ignored.
- One report per agent per rule per cooldown, and a venue-wide cap an hour,
  beyond which hits are merged into one report.

**Sending.** Brightside makes sure Thor exists (`God.ensure`, which costs no
job once he is configured), then, as the venue operator who owns him, gives
him an `agent:request` task with the observation; the job record says the
venue asked. If he cannot answer — no model configured, or the call fails —
the watcher puts a plain warning in the venue's Inbox instead, so an
observation is never lost silently.

**Thor's side.** His prompt gains one paragraph of judgement: observations
from Brightside's watch are mechanical facts about what happened, reason to
look and never on their own to stop; many are benign; patterns the owner has
vouched for go in his memory, so a repeat closes in one short turn. The
task's fields are described where the watcher builds it, not in his prompt.

**Settings.** Rules and thresholds are configuration with defaults, so an
owner who finds one noisy can turn it down or off.

**Cost.** Nothing while nothing fires; one Thor turn and his reads for each
report. Rule 2 decides the noise: it starts strict (outside content in the
same cycle, a short list of consequential operations), and Thor's memory
absorbs what is normal for this owner.

**Tests**, of mechanism only:

- Synthetic event sequences through the rules, on an injected clock: each
  rule fires on its shape and not on a near miss; cooldown and cap hold; the
  gods and stopped agents never fire.
- On a real venue: a user's agent on the venue's tool-calling test model
  trips a rule set that counts its tool as outward; the test follows the
  dispatch the watcher returns to Thor's task and checks it names the agent
  and the rule. The same activity from a god sends nothing, checked after
  the positive case has completed.
- With Thor unable to answer, the warning reaches the venue's Inbox.

## Option B: a patrol

Brightside schedules, as the operator, a recurring task for Thor: review what
users' agents have done since the last round.

- Scheduling is an action that persists, so it belongs with keeping the gods
  configured, reconciled like their configuration so restarts never stack a
  second schedule — never at launch.
- Thor needs a read summarising activity since a time — per agent, cycles and
  tool calls by name — or he pages through conversations at great token
  cost. That is a new Brightside read operation.
- Every round is a model call and its reads, whatever happened. Hourly is
  twenty-four turns a day that mostly find nothing; daily is cheap, but harm
  lands a day before he looks.
- He reads far more attacker-controllable material than a targeted
  observation points him to — the opposite of "no further than the report
  needs" — and every round exposes Thor himself to whatever is hidden in it.
- In its favour: judgement over everything, so it can catch what no rule
  anticipated.

## Option C: both, cheaply

Option A, plus a digest for weak signals: hits too slight to report at once
(a single refusal, a pattern the owner has vouched for before) are kept, and
a scheduled Brightside operation hands Thor the list only when it is not
empty, so a quiet day still costs no model call. It is A with a schedule
added, and can follow A rather than come with it.

## To decide

- Which option: A is recommended, and C can follow it.
- Whether rule 2 is in from the start or added once the others have run for
  a while.
- Whether the fallback warning is wanted, or a Thor who cannot answer should
  simply leave the observation in the log.

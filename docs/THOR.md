# Thor, the guardian

Thor protects the owner against adversaries: instructions smuggled into
content that turn an assistant against them, an agent acting beyond what they
asked, outsiders probing for access or pressing for secrets. He is one of the
gods; what they share — the bridges, asking the owner, answering to Odin — is
in [GODS.md](GODS.md). This is what is his alone. Code: `Thor`.

- **He responds to reported threats** from the owner's assistants, the other
  gods and the owner. Routine activity causes no Thor turns.
- **His work stays focused.** No automatic tool-call screening, periodic
  summaries or background patrols; model calls go towards concrete incidents.
- **He investigates before he acts.** A report may be mistaken or itself the
  attack, so he never acts on its say-so alone.
- **He can change most things for regular users**, and does the least that
  ends the threat; suspension, which keeps everything, usually comes first.
- **Some things are never his**: secrets, grants, the user's Inbox, the gods
  and Njord's treasury.
- **He tells the owner what he did** and undoes it on their answer, and
  every change he makes is reported to Odin.

## Why Thor

An assistant spends its day reading what others wrote: pages, mail,
messages, files. Any of it can carry instructions meant to turn the
assistant against its owner, and an assistant cannot be its own guard. What
it can do is notice and tell someone, and that someone needs the power to
stop it at once and the judgement to know when.

Stopping cannot wait for the owner. Harm from a compromised agent is under
way while a request sits in the Inbox, so Thor acts first. He has broad
reach over the venue's regular users, and he answers for it after the fact:
every change is reported to Odin, and the owner can undo it. The other
choice was a guardian that could only warn. That is safe against a
mistaken Thor, but useless against a real attack.

He is a god rather than a skill because the power must sit outside the
agents he may need to stop. An assistant cannot suspend its siblings or take
back what they were given, and must not be able to. And a guardian that ran
inside the owner's namespace would share its weaknesses.

## What reaches him

The owner's assistants report what they suspect with `brightside:alert-thor`;
the `security` skill teaches them when ([SKILLS.md](SKILLS.md)). Odin reports
a request that looks like an attack, Njord a payment request that does, and
the owner may tell him directly.

Each report should describe a concrete incident, once. Ordinary mistakes,
failed tasks and uncertainty are not attacks. Thor investigates only as far
as the report needs, responds, then finishes. A report that does not hold up
closes without a change or an Inbox warning.

This is deliberately a minimal response service. Continuous detection and
routine reviews would create model work for Thor, and changes would create
more for Odin. They are outside his present role.

## What he can change

He reads enough of the agent concerned to judge — its record, its
conversations, what it wrote — and no further than the report needs. Then,
inside any of this venue's users' namespaces, he can stop, restart,
reconfigure or remove an agent, taking away its tools or who may talk to it;
edit or remove workspace data; cancel schedules; and disconnect connected
accounts. He tells the owner through the Inbox and undoes a change on their
answer; an attack that needs nothing changed gets the same warning.

## What is never his

Nothing he can run reveals or sets a secret, grants access, creates agents or
schedules, talks to an agent as the user, or answers the user's Inbox.
Changes to Brightside itself are Odin's, and Thor asks him for them. The gods
are beyond his reach, and so is Njord's treasury: his bridge lends sudo only
into users' namespaces, and never into one another god keeps.

## Future work

Thor may gain interception of risky actions before they run, or proactive
investigation that looks for threats without waiting for a report. These are
future possibilities, not part of his current behaviour. Any such work should
justify its token cost and keep routine activity cheap; for now, his focus
is responding to reported threats.

## Known limits

- Covia cannot revoke a grant once issued; a delegation lasts until it
  expires. Thor can only stop the agent holding it.
- Brightside's users have no sign-in keys of their own to revoke, and a
  token issued to a tool outside the app lasts until it expires
  ([CONFIGURATION.md](CONFIGURATION.md#from-claude-code)).
- Brightside re-applies its own configuration to the main assistant when a
  chat first sends, so tools Thor takes from that agent come back then; a
  suspension stays.
- Thor depends on someone reporting a threat; he does not automatically
  detect or block a credential leaving in a tool call.
- Tools reaching the venue over MCP act as the owner, not as an agent;
  suspending an agent does not stop those tools.

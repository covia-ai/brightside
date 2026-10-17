# Thor, the guardian

Thor protects the owner against adversaries: instructions smuggled into
content that turn an assistant against them, an agent acting beyond what they
asked, outsiders probing for access or pressing for secrets. He is one of the
gods; what they share — the bridges, asking the owner, answering to Odin — is
in [GODS.md](GODS.md). This is what is his alone. Code: `Thor`, `ThorWatch`.

- **Trouble reaches him two ways**: reports from the owner's assistants, Odin
  and the owner, and a watch Brightside keeps on agents' tool calls, which
  holds a key on its way out until he has answered.
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
agents he watches. An assistant cannot suspend its siblings or take back
what they were given, and must not be able to. And a guardian that ran
inside the owner's namespace would share its weaknesses.

## What reaches him

The owner's assistants report what they suspect with `brightside:alert-thor`;
the `security` skill teaches them when ([SKILLS.md](SKILLS.md)). Odin reports
a request that looks like an attack, Njord a payment request that does, and
the owner may tell him directly.

The watch needs no one to report. It is a guard on every user agent's tool
calls — Covia's tool guards, which see each operation call before it runs —
and sorts each call into one of three tiers, with one rule each:

| Tier | Rule | What happens |
|---|---|---|
| High | something shaped like a key, token or private key in a call that sends out: a message, a post, another agent | the call waits for Thor, up to two minutes, and runs only if he allows it |
| Medium | such a key in any other call outside the secret store | the call runs, and Thor is told at once |
| Low | a call that sends out, with no key | the call runs, and Thor gets a summary of the last half hour's, if there were any |

- **One rule a tier, on purpose**: every report is a Thor turn, and every
  change he makes is an Odin turn as well.
- **Watching costs nothing.** The guard is in-process and starts with the
  venue, so a launch still submits no jobs. Only a report does: a task for
  Thor, sent as the venue operator who owns him, once he is configured.
- **The task carries facts, not content**: the agent, its owner, the tool and
  the conversation, or for a summary how often each agent used each tool;
  none of the input, which an attacker may have shaped. Thor reads that
  through his bridge.
- **A held call fails closed.** Anything but Thor's yes — a refusal, no
  answer in time, a failure — refuses the call, and the agent is told why.
- **It stays quiet.** The venue's own agents pass untouched, Thor's own calls
  among them, so a held call never waits on him. An agent reported at medium
  is not reported again for an hour. A report that does not hold up closes
  changing nothing, so Odin hears nothing either.
- **What it looks for**: provider API keys (`sk-` keys of the Anthropic and
  OpenAI kind, GitHub, Slack, AWS, Google) and PEM private keys. The calls
  that send out are the Discord, Slack, Telegram and WhatsApp sends, A2A
  sends, and Moltbook posts and comments. A tool is named after its adapter,
  so the secret store's own, where a key belongs, are `secret_*`.

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

## Known limits

- Covia cannot revoke a grant once issued; a delegation lasts until it
  expires. Thor can only stop the agent holding it.
- Brightside's users have no sign-in keys of their own to revoke, and a
  token issued to a tool outside the app lasts until it expires
  ([CONFIGURATION.md](CONFIGURATION.md#from-claude-code)).
- Brightside re-applies its own configuration to the main assistant when a
  chat first sends, so tools Thor takes from that agent come back then; a
  suspension stays.
- Only agents' tool calls are watched. Tools reaching the venue over MCP act
  as the owner, not as an agent, and Thor could not stop them anyway.
- Recovery phrases, and tokens such as the tool token, are not recognised by
  the watch.
- Refused tool calls would be a good second signal for the watch, but a
  refusal comes back only as error text, and Brightside never decides on
  wording.
- The calls gathered for the next summary are kept in memory; those still
  waiting when Brightside quits are not summarised.

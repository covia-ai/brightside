# Thor, the guardian

Thor protects the owner against adversaries: instructions smuggled into
content that turn an assistant against them, an agent acting beyond what they
asked, outsiders probing for access or pressing for secrets. He is one of the
gods; what they share — the bridges, asking the owner, answering to Odin — is
in [GODS.md](GODS.md). This is what is his alone. Code: `Thor`, `ThorWatch`.

- **Trouble reaches him two ways**: reports from the owner's assistants, Odin
  and the owner, and a watch Brightside keeps for one thing itself, a
  credential leaving.
- **He investigates before he acts.** A report may be mistaken or itself the
  attack, so he never acts on its say-so alone.
- **He can change most things for regular users**, and does the least that
  ends the threat; suspension, which keeps everything, usually comes first.
- **Some things are never his**: secrets, grants, the user's Inbox, the gods
  and Njord's treasury.
- **He tells the owner what he did** and undoes it on their answer, and
  every change he makes is reported to Odin.

## What reaches him

The owner's assistants report what they suspect with `brightside:alert-thor`;
the `security` skill teaches them when ([SKILLS.md](SKILLS.md)). Odin reports
a request that looks like an attack, Njord a payment request that does, and
the owner may tell him directly.

The watch needs no one to report. It looks for a user's agent passing
something shaped like a key, token or private key to anything but the secret
store, the shape of a credential leaving:

- **One rule, on purpose.** It is rare and nearly always worth a look, so the
  watch adds almost nothing to the gods' work: every report is a Thor turn,
  and every change he makes is an Odin turn as well.
- **Watching costs nothing.** It is an in-process subscription to the venue's
  agent event stream, started with the venue, so a launch still submits no
  jobs. Only a report does: a task for Thor, sent as the venue operator who
  owns him, once he is configured.
- **The task carries facts, not content**: the agent, its owner, the tool and
  the conversation, but none of the input, which an attacker may have
  shaped. Thor reads that through his bridge.
- **It stays quiet.** The venue's own agents are not watched, and an agent
  reported is not reported again for an hour. Thor takes a report as grounds
  to look: if a credential is leaving he suspends the agent and tells the
  owner, and if not he closes it changing nothing, so Odin hears nothing
  either.
- **It looks for** provider API keys (`sk-` keys of the Anthropic and OpenAI
  kind, GitHub, Slack, AWS, Google) and PEM private keys. A tool is named
  after its adapter, so the secret store's own, where a key belongs, are
  `secret_*`.

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
  refusal reaches the event stream only as error text, and Brightside never
  decides on wording.

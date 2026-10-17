# Thor's watch

Brightside keeps a watch for Thor, so he hears of one kind of trouble without
anyone reporting it: a user's agent passing something shaped like a key,
token or private key to anything but the secret store — the shape of a
credential leaving. Thor himself is in [GODS.md](GODS.md); the code is
`ThorWatch`.

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
  reported is not reported again for an hour.
- **It looks for** provider API keys (`sk-` keys of the Anthropic and OpenAI
  kind, GitHub, Slack, AWS, Google) and PEM private keys. A tool is named
  after its adapter, so the secret store's own, where a key belongs, are
  `secret_*`.

## Known limits

- Only agents' tool calls are watched. Tools reaching the venue over MCP act
  as the owner, not as an agent, and Thor could not stop them anyway.
- Recovery phrases, and tokens such as the tool token, are not recognised.
- Refused tool calls would be a good second signal, but a refusal reaches the
  event stream only as error text, and Brightside never decides on wording.

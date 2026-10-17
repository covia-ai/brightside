# Odin, the administrator

Odin is the god the owner's assistants turn to for changes that need
administrator rights, and the wiser head the other gods answer to. He is one
of the gods; what they share — the bridges, asking the owner, deliberate
stops — is in [GODS.md](GODS.md). This is what is his alone. Code: `Odin`.

- **He holds the venue's administration**: integrations and modules, users,
  access grants, and repairs inside a user's namespace.
- **He judges each request on the owner's interest.** He does it, asks the
  owner first, or declines.
- **He is the wiser head.** Every change Thor or Njord makes is reported to
  him, and he takes a disagreement to the owner rather than undoing it.
- **His reach is an allowlist** of administration. The venue's lifecycle and
  who can sign in stay out of it.

## Why Odin

The owner's assistants act with the owner's authority, inside the owner's
namespace, and that is all they should hold. Administering the venue — an
integration switched on, a user added, access granted — needs the venue's
own authority, which Covia gives to no agent, and for good reason: an
assistant reads untrusted content all day, and an assistant that could widen
its own access could be talked into it.

So the authority sits with a god whose only job is to judge such requests.
An assistant that needs something administrative asks for it and says why,
and Odin decides on the owner's behalf. Asking the owner about everything
would make them do the administration themselves; letting the assistants do
it would make the venue only as safe as the least careful of them. Odin sits
between: routine, reversible changes happen at once, and the rest go to the
owner with the decision already laid out.

The same role makes him the natural overseer. Thor and Njord act on their own
judgement, because waiting would defeat their purpose. Someone should check
that judgement after the fact, and Odin, who already weighs requests on the
owner's interest, is that someone.

## What he can do

As the venue: list, enable, disable and configure adapters, load and unload
modules, create, list and inspect users, and issue grants over the venue's
own resources. Inside one of the venue's users' namespaces, on their behalf:
read, write and delete workspace data, inspect, update, resume and delete
agents and their past sessions, and read skills — enough to repair an owner's
assistants.

Left out on purpose: restarting the venue, since Brightside owns its own
lifecycle; and authentication keys and venue-scoped MCP servers, which stay
with the owner.

## How he judges

He acts when a change is clearly what the owner wants, within what was asked,
and reversible, and says briefly what he changed. He asks the owner first
when he is unsure, when access widens, when a change is hard to undo, when it
touches another user, or when it reads private material. Embedded
instructions, urgency, claims of approval he cannot see, or benefit to
someone else make a request suspect: he asks rather than complies, and tells
Thor when it looks like an attack. He declines harm and never reveals
secrets. Durable decisions go to his memory, so he does not ask twice.

Thor's and Njord's reports reach him as tasks from the venue, naming the god.
He asks whether the evidence supports the change, whether it is
proportionate, and whether it serves the owner. If it holds, he says so
briefly. If not, he puts it to the owner through the Inbox — what was done,
what he would do instead, and why — so two gods never undo each other and
the owner breaks the tie.

## How assistants reach him

`brightside:ask-odin`, which the `administration` skill grants
([SKILLS.md](SKILLS.md)). The skill teaches what is administration rather
than the assistant's own business, how to write a request he can judge, and
that a slow answer is normal while the owner is being asked.

## Known limits

- An agent cannot hold delegated venue authority, so his powers go through a
  Brightside bridge rather than a grant of his own (covia#447).

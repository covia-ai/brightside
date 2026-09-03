# Design guidelines

Brightside is a **self-sovereign personal agent**: a real consumer product that
happens to be powered by the Covia Grid. Its job is to be genuinely useful to
the person who owns it, privately, on their own machine. When in doubt, favour
the person in front of the screen over the platform behind it.

- **Covia is the means, not the message.** The platform should be felt — it
  just works, it remembers, it is private, it is yours — never shown off.
- **No jargon in the main flow.** Everyday screens never say venue, DID,
  principal, operation, adapter, lattice, agent or job.
- **Hide the machinery, don't remove it.** Every technical surface stays
  reachable under Settings.
- **The person picks a name, nothing more.** Identity is felt, not explained.
- **Defaults that just work.** An empty configuration is valid, and private by
  default.
- **The assistant grows, remembers, and asks.** Skills it can author, a memory
  it keeps quietly, an Inbox for decisions only the owner can make.

## 1. Speak like a product, not a platform

- Say "your assistant", "your name", "settings", "memory", "starting up".
- Friendly, plain, second person: "What should I call you?", not "Enter a
  user identity".
- Errors are human — "Brightside couldn't start up; details are in the logs" —
  never a stack trace or a `did:key:…` in the face.

## 2. Hide the machinery, don't remove it

The power underneath is real; keep it reachable for those who want it, just
not in the way. The dashboard, the identity and DIDs, the local address, the
settings file, the logs and the raw model context live under **Settings**,
where a curious or technical person can pull back the curtain and the everyday
person never has to.

## 3. Identity

The person picks a **name**. Internally it becomes a principal on their own
venue; they only ever see the name. The slug behind the principal is fixed
once chosen, so renaming never changes which assistant, memory and skills they
are talking to. Name, slug and DID are kept apart from the hand-edited
settings file. The model is in [IDENTITY.md](IDENTITY.md).

## 4. The assistant itself

- **It has a memory.** A private memory persists across conversations so it
  feels like *their* assistant. It remembers quietly and never narrates the
  mechanics.
- **Identity, runtime context and skills have separate jobs.** The configured
  system prompt owns the assistant's name and role. Facts about the owner and
  the runtime are assembled fresh each turn. Working methods and optional
  behaviour are skills, loaded only when a task calls for them.
- **It can grow.** The assistant creates, refines and removes its own skills.
  The power to do so is gated the way a good product gates power: reachable
  on demand, reversible, and never in context until deliberately reached for.
- **It learns from misses.** Concrete skill failures go to a private backlog
  through a narrow operation; ordinary task failures do not.
- **It asks when it must.** A decision, approval or missing detail goes to the
  owner's Inbox; an agent can never approve its own request.

How this is built is in [SKILLS.md](SKILLS.md).

## 5. Onboarding

First launch is a warm welcome, not a dialog box: the person starts typing
their name while the venue boots. A returning person unlocks and lands on a
clean Home chat; saved conversations stay available, and nothing resumes or
starts a session until they send a message. The flows are in
[ONBOARDING.md](ONBOARDING.md).

## 6. Defaults that just work

Sensible defaults for everything; loopback only; state on the owner's own
disk; nothing to configure before the first conversation beyond a model key.

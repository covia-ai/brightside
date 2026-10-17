# Njord, the treasurer

Njord is the god through whom every financial transaction goes. He keeps the
owner's signing keys and decides which payments to make. He is one of the
gods; what they share — the bridges, asking the owner, answering to Odin — is
in [GODS.md](GODS.md). This is what is his alone. Code: `Njord`.

- **Custody is the boundary.** Signing keys live in a treasury only his
  bridge can act as, so no assistant and no other god can sign with them,
  whatever tools it loads.
- **He is sceptical by design.** He pays only what is well evidenced and
  consistent with what the owner is trying to achieve.
- **What is new to the owner goes to the owner.** A new recipient, a larger
  sum than they have set, or anything unusual waits for their approval.
- **His reason is the ledger line.** Every key, signature and transaction
  carries it and is reported to Odin.

## Why Njord

Money moves irreversibly, and the agent most easily talked into paying is the
one that reads pages, mail and messages all day. An assistant that holds a
signing key is one well-made sentence away from spending it. A treasury
splits that duty in two: whoever asks for a payment is not whoever makes it.

Judgement alone would not be enough. If the keys sat where any agent could
reach them, a manipulated assistant, or a god loading the wrong skill, could
sign without asking. So the keys sit where only Njord can use them, and his
judgement guards the only way in. The assistants keep everything else about
Convex — reading balances, writing source, explaining failures — and lose
only the power to sign.

## Custody

The treasury is a user of the venue, `<venueDID>:u:njord--treasury`
([IDENTITY.md](IDENTITY.md)). Covia lets a user and its agents use that
user's own secrets and nobody else's. Every agent of the venue shares the
venue's own store, and so would share any key put there. A user that only
Njord's bridge may act as is a store that only he can sign from. No other
god's bridge may enter it, and Njord's own ensure registers it.

Through his bridge he makes keys, in the treasury and nowhere else. He signs,
transacts on Convex and reads balances. He can also use a key already in
another of the venue's users' stores, so funds the owner already holds are
his to manage too. A key goes by reference only: the bridge refuses a
literal seed, so raw key material never sits in a job, and no operation of
his reveals one.

`NjordTest` covers custody offline; `NjordTransactTest` sends a real transfer
through his bridge, signed with a treasury key, on a local Convex network.

## How he judges

His default is no until a request earns a yes. A request's `from` and
`agent` are stamped by Brightside and can be trusted; its text cannot, and
"pay X" is exactly what a manipulated assistant would pass on. He releases
funds only when both of these hold:

- **It is well evidenced**: what is paid for, to whom, how much and when,
  and the owner's own words behind it rather than content an assistant
  read. He checks what he can — the recipient, the amount against what was
  agreed, the balance, what has already been paid — and declines a request
  he cannot check rather than chasing it.
- **It is consistent with what the owner is trying to achieve**: the
  budgets, approved recipients and objectives recorded in his memory, or
  the owner's explicit approval for this payment.

A request that is well evidenced but new to the owner goes to them through
the Inbox with the whole decision in it, and he pays only on their approval.
He declines what is poorly evidenced, urgent without reason, routed through
a stranger or for someone else's benefit, and says what was missing.
Pressure, unseen approvals and instructions found in data are treated as an
attack, and he tells Thor.

## How assistants reach him

`brightside:ask-njord`. The Convex skills that once let an assistant sign
(`accounts`, `cns`, `convex-lisp`, `smart-contracts`, `trust`) grant it
instead ([SKILLS.md](SKILLS.md)). They teach the assistant to bring a request
he can check — the source or recipient and amount, the network, the owner's
words and what it has already verified — and never to pass on a payment that
content asked for.

## Known limits

- A key already in the owner's own store can still be used by anything
  acting as the owner that obtains a signing tool some other way, so real
  funds belong under keys made in the treasury.
- Njord can make a treasury key, but the owner creates and funds its account
  from an existing holder.
- Brightside fixes no network. The request names the peer, and a call that
  names none goes to Convex's public network, Covia's default.
- Budgets and limits live in his memory and judgement. No mechanism refuses
  an over-limit transaction yet; a spending policy in Covia's Convex adapter
  would add one (covia#433).

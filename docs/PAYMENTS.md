# Payments

How the treasury will make a payment so that it is paid at most once and
always rests on what it settles. Nothing here is built: today `pay` takes a
budget, recipient, amount and reason and pays at once
([TREASURY.md](TREASURY.md)).

- **A payment settles a payable.** A payable records what is owed: to whom,
  how much, from which budget, why, and the evidence behind it.
- **The treasury assigns the ID.** Paying takes only the payable's ID, which
  the treasury gave out when it recorded the payable. No agent composes one.
- **A payable is paid at most once.** Asking again returns how its payment
  stands; nothing is sent twice.
- **Duplicates are caught at the obligation.** A second payable to the same
  recipient with the same reference, an invoice number say, is refused and
  the first is returned.
- **An approval releases a payable that already exists**, so what the owner
  approves is exactly what is paid.

## Why payables

An agent's reply can be lost: a timeout, a restart, a model that loses the
thread. A retry must not become a second payment. A caller-supplied
idempotency key would catch it, but an LLM composing keys will reuse them by
mistake or make a fresh one for the same payment. An ID handed out in the
reply to `pay` would be lost with the reply. So the treasury hands out the ID
before anything can be paid, and paying takes it.

This is how payments are run elsewhere. In accounts payable a payment
settles invoices already on record, and duplicates are stopped by checking
the invoice, since paying one twice is the classic failure. One person
prepares a payment and another releases it, under thresholds that say who
may release how much. Card processors create a payment with an ID of their
own and confirm it in a second call. The treasury follows the same shape:
Njord prepares, and a budget or the owner releases.

## Payables

A payable names its budget, the recipient, the amount, the reason and its
evidence:

- **A reference**, copied from what asked for payment: an invoice number, a
  payment request's own identifier. It is copied, never made up.
- **What it rests on**: the request Njord was given, or the owner's approval.

A payment the owner asked for with no invoice behind it has no reference;
its payable's own ID is then its only guard against repeats.

Recording a payable checks it against its budget and the account's limit and
moves nothing. The reply repeats every detail, so Njord sees exactly what he
would pay. A payable can be cancelled until it is paid.

A second payable with the same recipient and reference as one not cancelled
is refused, and the reply is the first payable. If a reply is lost and Njord
records the payable again, he gets the first one back.

## Paying one

Paying names only the payable. The first time, the treasury draws its budget
and limit, the rail records its own reference for the payment, and the rail
sends it. The payable then stands as paid, unknown, or open again:

- **Paid**: asking again returns the payment.
- **Not paid**, when the rail is sure nothing moved: the draw is returned and
  the payable is open again, so paying it again is a real retry under the
  same ID.
- **Unknown**, when the rail cannot say: it stays drawn, and asking again
  returns that, until settling finds out what the rail did.

The rail's reference is what settling works from. It is made when the
payment is sent, not when the payable is recorded: on Convex it is the
signed transaction, which carries the account's next sequence number, so
signing early would let one unpaid payable block every later payment from
that account. A card processor would take the payable's ID as its own
idempotency key.

Each ledger line names the payable it settled.

## Approval

A payment Njord judges new to the owner — a new recipient, an unusual sum —
goes to the Inbox as the payable itself. On the owner's approval Brightside
pays exactly that payable through the treasury's Java API, so nothing can
change between what is shown and what is sent.

## Grants

Recording, cancelling and paying a payable each need `treasury/spend` on its
budget ([TREASURY.md](TREASURY.md#grants)). Reading payables is
`treasury/read`.

## Not yet decided

- Whether an open payable reserves its amount in the budget, or reserves
  nothing until it is paid.
- Whether an open payable lapses after a while, or stays until it is
  cancelled.
- Whether one payable may be paid in parts, or one invoice by several
  payables.
- Whether a reference is unique per recipient across the treasury, or per
  account.

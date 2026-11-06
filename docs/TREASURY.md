# The treasury

The treasury holds the accounts Njord manages, the spend limit the owner has
approved on each, the budgets Njord draws from those limits, and the ledger of
every payment. It is a Brightside adapter written to move into Covia, and
nothing in it belongs to any one network. Code: `brightside.treasury`
(`TreasuryAdapter`, `Rail`, `ConvexRail`). Accounts, limits, budgets, paying
and the ledger are built, on Convex, and Njord reaches them through his
bridge; the rest of this doc is ahead of the code where
[Not built yet](#not-built-yet) says so.

- **Generic, with rails beneath.** The treasury speaks of accounts,
  currencies, amounts and payments. A rail is how money moves on one network
  or payment system; Convex is the first and the exemplar. Currencies are
  named apart from rails.
- **The owner's controls are outside the venue.** Adding an account, setting
  its limit and holding its credential are done by Brightside's own code,
  never by an operation, so no grant made inside the venue, by any agent or
  by the venue itself, reaches them.
- **Limits are the owner's.** Each account has a spend limit: the total the
  owner has approved for spending from it. Only the owner sets it, in the
  app, and Njord asks for a raise through the Inbox.
- **Budgets are Njord's.** A budget is an allowance of one currency, drawn
  from one account's limit, which Njord creates, lowers and cancels on his
  own judgement. A grant on a budget is the power to spend from it.
- **Spending is the treasury's own accounting.** A payment draws down its
  budget and its account's limit together, or fails, as an overdraft would.
  No tool is guarded; there is simply nothing left to spend.
- **Every payment is a ledger line**: what, to whom, how much, from which
  budget, on whose authority and why.

## Why a treasury

Njord's custody already keeps the keys from every other agent, but how much
he may spend lives only in his memory and judgement. That breaks the gods'
rule that power is a mechanism and judgement a prompt
([GODS.md](GODS.md)). Covia's raw transaction operation cannot close the gap:
nothing can read "how much, to whom" reliably out of arbitrary source. The
treasury gives spending a typed shape, so the amount at risk becomes a number
the owner sets, and Njord's judgement decides only how to use it.

## Rails, currencies and accounts

A rail is how money moves: a Convex network, SEPA, a card network. A currency
is what moves: GBP, CVM, a token. The two vary apart. One rail may carry
several currencies, as a Convex network carries CVM and its fungible tokens,
and one currency may move over several rails, as GBP goes by bank transfer or
by card.

A currency is named apart from any rail: ISO 4217 for fiat, CAIP-19 for an
asset on a chain. A token belongs to its chain, so its name includes the
network, and the same token on two chains is two currencies.

An account is one place funds are held on one rail, possibly in several
currencies, with the credential that lets the treasury spend from it. What a
credential is belongs to the rail: a signing key on a chain, an API key for a
card processor, a token for a bank. The treasury holds it itself, in its own
state, where no operation reaches it, and hands it only to the account's
rail to pay. No venue user's secret store holds it, so nothing that can act
as a user of the venue can use it either. Accounts and
recipients are named in their rail's own standard form, CAIP-10 on a chain
and a `payto:` URI for a bank-style account, or a form the rail defines where
none exists. The treasury stores and compares these names; only the rail
reads them.

A rail is a `Rail`: how one network reads a balance and makes a payment. A
rail delegates to an existing Covia operation where one fits without the
credential. The Convex rail pays CVM: it reads balances through
`convex/query`, and signs and sends each `transfer` itself with the Convex
library, since its key is the treasury's and in no Covia user's store. The
ledger is each payment's record. It counts a payment as not paid only when
it failed before it was sent or Convex answered that it refused it; a
timeout or a lost connection leaves it unknown. Other rails, x402 or a card
processor say, carry their own code in the same way.

## Spend limits

An account enters the treasury with a limit of nothing. The limit is a
running total: what the owner has approved for spending from that account
over its life. When it is used up, Njord asks for more.

A raise is the owner's decision. Njord asks through the Inbox, laying out the
account, the new limit and why. The owner's approval is what raises it:
Brightside recognises a treasury request in the Inbox and, on approval,
applies the new limit through the treasury's Java API, the only way a limit
is set. Lowering a limit is the owner's as well, in the app, and stops
budgets drawing beyond it.

## Budgets

A budget is an allowance: permission to draw up to an amount of one currency
from one account, for a purpose or for an agent. Creating one moves nothing
on the network. Njord creates, lowers and cancels budgets within what the
account's limit has left, and the treasury refuses a budget that would take
the account's budgets past it.

A budget belongs to one account, so every budget sits under one limit and
the owner can always see where its money would leave from. Where the owner
allows it, Njord may move what remains of a budget to another account in the
same currency, to pay GBP by card rather than by bank, say. The owner's
permission is a standing grant to move budgets onto that account, apart from
managing budgets on it. The move must fit the new account's limit; what was
already drawn stays on the old one.

Budgets may add up to more than an account holds, so a payment within budget
can still fail at the rail for want of funds. The treasury shows that
overcommitment beside the balances, and the limit still caps what can leave.

## Paying

A payment names its budget, the recipient, the amount and a reason. The
caller must hold a grant on that budget. In one step, saved before the rail
is asked, the treasury draws the budget and the limit down and writes the
ledger line; then it settles the line with what the rail said. Paid; not
paid, when the rail is sure nothing moved, and the budget has the amount
back; or unknown, when the rail cannot say, and it stays drawn. How a
payment will instead settle a payable the treasury recorded first, so that
it is paid at most once, is in [PAYMENTS.md](PAYMENTS.md).

Today Njord is the only spender: his run bridge mints, for each call, a grant
on the account or budget named and nothing broader. Once an agent can hold a
grant of its own, Njord can delegate a budget to an assistant, a small one
for paying web services say, and those payments need no turn of his.

## Grants

Authority over the treasury is Covia's ordinary capabilities
(`venue/docs/UCAN.md` in the Covia repository): a resource path, an
ability, and a grant that covers everything under its path. Accounts and
budgets are the venue's own resources, `<venueDID>/treasury/accounts/<id>`
and `<venueDID>/treasury/budgets/<id>`, so the venue holds every ability and
anyone else presents a grant it signed. Budgets are flat, each at a path of
its own that records the account it draws on, so moving a budget leaves its
path, and every grant on it, as it was.

Grants reach no further than the operations. Adding an account, setting a
limit and the credentials have none, so no grant, whoever signs it, reaches
them; the owner reaches them through the app.

| Ability | On | Lets the holder | Held by |
|---|---|---|---|
| `treasury/read` | the treasury, an account or a budget | list, and read the ledger | Njord, Odin, the owner |
| `treasury/spend` | a budget | pay from it | Njord; later an assistant |
| `treasury/budget` | an account | create, lower and cancel budgets drawing on it | Njord |
| `treasury/move` | an account | move a budget onto it from another account in the same currency | whom the owner grants it to |
| `grant/treasury/spend` | budgets | have spending on a budget granted to another | Njord, once agents hold grants |

Delegation needs nothing new: Covia's grant issuance already lets a holder of
`grant/<ability>` on a resource have the venue grant that ability under it.

## Operations

| Operation | Does | Needs |
|---|---|---|
| `account-list` | accounts with limit, drawn, budgets and balance | `treasury/read` |
| `budget-create`, `budget-lower`, `budget-cancel` | manage a budget within its account's limit | `treasury/budget` on the account |
| `budget-move` | move what remains of a budget to another account in the same currency | `treasury/budget` on the account it leaves, `treasury/move` on the one it joins |
| `budget-list` | budgets with what remains of each | `treasury/read` |
| `pay` | pay from a budget: recipient, amount, reason | `treasury/spend` on the budget |
| `ledger` | the payments made | `treasury/read` |

Adding an account, with its credential and a limit of nothing, and setting
its limit are the treasury's Java API (`TreasuryAdapter`), which only the
app calls.

## Njord's place

The treasury's operations are Njord's granted operations: his run bridge
runs each as him, under a grant the venue signs for exactly the account or
budget it names ([GODS.md](GODS.md)). Each that changes something needs his
reason and is reported to Odin. He keeps Covia's raw `transact` and `sign`,
for what a payment cannot express. Those sign only with keys in a venue
user's store, and an account's credential is in none, so they cannot step
around its limit. His judgement still decides each payment he makes; the
treasury decides only that it fits.

`TreasuryTest` covers the grants and the accounting over a rail that moves
nothing, and Njord's bridge; `TreasuryConvexTest` pays over a local Convex
network.

## Not built yet

- The owner's controls in the app, and the Java API behind them. Until
  then adding an account and setting a limit are operations that only the
  venue itself may run.
- Credentials held by the treasury. Until then an account names a key in a
  venue user's store, Njord's treasury, and the Convex rail signs there
  through Covia's `convex/transact`, so the key is reachable by what can act
  as that user, Njord's raw signing included.
- The Inbox request to raise a limit, and Brightside applying it on the
  owner's approval.
- Payables, and paying one by its ID ([PAYMENTS.md](PAYMENTS.md)).
- `budget-move` and `treasury/move`.
- Settling a payment left pending, by a stop between its draw and the
  rail's answer, or unknown: it stays drawn until something finds out what
  the rail did.
- Rails other than Convex, and currencies other than CVM.

## Not yet decided

- How a credential comes into the treasury: made there, or brought in from
  where it is held now.
- How a contract call that spends, rather than a plain transfer, is paid
  through a rail.
- Whether budgets expire, and whether a limit may also renew each period.
- Delegating budgets to assistants, once agents can present grants
  (covia#447).

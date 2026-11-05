# The treasury

The treasury holds the accounts Njord manages, the spend limit the owner has
approved on each, the budgets Njord draws from those limits, and the ledger of
every payment. It is a Brightside adapter written to move into Covia, and
nothing in it belongs to any one network. This doc is ahead of the code: today
Njord signs through Covia's Convex operations directly
([NJORD.md](NJORD.md)).

- **Generic, with rails beneath.** The treasury speaks of accounts,
  currencies, amounts and payments. A rail is how money moves on one network
  or payment system; Convex is the first and the exemplar. Currencies are
  named apart from rails.
- **Limits are the owner's.** Each account has a spend limit: the total the
  owner has approved for spending from it. Only the owner raises it, through
  the Inbox.
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
card processor, a token for a bank. The treasury holds it only as a reference
to a secret in a custody it spends from, and never reads it. Accounts and
recipients are named in their rail's own standard form, CAIP-10 on a chain
and a `payto:` URI for a bank-style account, or a form the rail defines where
none exists. The treasury stores and compares these names; only the rail
reads them.

A rail is a `Rail`: how one network reads a balance and makes a payment. A
rail that fits an existing Covia operation delegates to it. The Convex rail
reads through `convex/query` and pays through `convex/transact`, so its
signing key is resolved by Covia from its reference, never seen by the
treasury, and
every payment keeps Covia's job record. A rail without such an operation,
x402 or a card processor say, carries its own code.

## Spend limits

An account enters the treasury with a limit of nothing. The limit is a
running total: what the owner has approved for spending from that account
over its life. When it is used up, Njord asks for more.

A raise is the owner's decision. Njord asks through the Inbox, laying out the
account, the new limit and why. The owner's approval is what raises it:
Brightside recognises a treasury request in the Inbox and, on approval,
applies the new limit as the operator. Njord's bridge cannot. Lowering a limit
is the owner's as well, and stops budgets drawing beyond it.

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
caller must hold a grant on that budget. The treasury draws the budget and
the limit down in one step, pays over the rail, and writes the ledger line;
if the rail fails, the draw is returned.

Today Njord is the only spender: his run bridge mints, for each call, a grant
on the account or budget named and nothing broader. Once an agent can hold a
grant of its own, Njord can delegate a budget to an assistant, a small one
for paying web services say, and those payments need no turn of his.

## Grants

Authority over the treasury is Covia's ordinary capabilities
(`venue/docs/UCAN.md` in the Covia repository): a resource path, an
ability, and a grant that covers everything under its path. Accounts and
budgets are resources of the treasury's own, under a `treasury/` segment.
Budgets are flat, each at a path of its own that records the account it
draws on, so moving a budget leaves its path, and every grant on it, as it
was.

| Ability | On | Lets the holder | Held by |
|---|---|---|---|
| `treasury/read` | the treasury, an account or a budget | list, and read the ledger | Njord, Odin, the owner |
| `treasury/spend` | a budget | pay from it | Njord; later an assistant |
| `treasury/budget` | an account | create, lower and cancel budgets drawing on it | Njord |
| `treasury/move` | an account | move a budget onto it from another account in the same currency | whom the owner grants it to |
| `treasury/limit` | an account | set its limit | the venue alone, on the owner's approval |
| `grant/treasury/spend` | budgets | have spending on a budget granted to another | Njord, once agents hold grants |

Delegation needs nothing new: Covia's grant issuance already lets a holder of
`grant/<ability>` on a resource have the venue grant that ability under it.

## Operations

| Operation | Does | Needs |
|---|---|---|
| `account-add` | bring an account under the treasury by its credential reference, with a limit of nothing | `treasury/budget` on the treasury |
| `account-list` | accounts with limit, drawn, budgets and balance | `treasury/read` |
| `limit-set` | set an account's limit | `treasury/limit` on it |
| `budget-create`, `budget-lower`, `budget-cancel` | manage a budget within its account's limit | `treasury/budget` on the account |
| `budget-move` | move what remains of a budget to another account in the same currency | `treasury/budget` on the account it leaves, `treasury/move` on the one it joins |
| `budget-list` | budgets with what remains of each | `treasury/read` |
| `pay` | pay from a budget: recipient, amount, reason | `treasury/spend` on the budget |
| `ledger` | the payments made | `treasury/read` |

## Njord's place

Njord's bridge gains the treasury's operations and keeps Covia's raw
`transact` and `sign`, for what a payment cannot express. Those sign only
with keys he can reach, so an account's limit holds as long as its
credential is not one of them. His judgement still decides each payment he
makes; the treasury decides only that it fits.

## New interfaces

| Interface | Who uses it |
|---|---|
| `brightside.treasury`, the adapter `treasury` and its operations above, its state in Brightside's adapter state ([APP_STATE.md](APP_STATE.md)) | Brightside |
| `Rail`, and `ConvexRail` delegating to Covia's Convex operations | the treasury |
| The `treasury/…` abilities above | Covia's grants |
| An Inbox request kind for raising a limit, which Brightside applies on approval | Njord, the owner |

## Not yet decided

- Where the credentials of the treasury's accounts are kept, so that
  nothing but the treasury, Njord's raw signing included, can use them, and
  how an account already held elsewhere comes under the treasury.
- Which principal's DID the treasury's resources sit under: the venue's, or
  a user the treasury keeps.
- How a contract call that spends, rather than a plain transfer, is paid
  through a rail.
- Whether budgets expire, and whether a limit may also renew each period.
- How an owner's approval runs a raise without Brightside applying it, once
  Covia has an ask that runs one call on approval (covia#592).
- Delegating budgets to assistants, once agents can present grants
  (covia#447).

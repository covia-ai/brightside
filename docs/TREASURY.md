# The treasury

The treasury holds the accounts Njord manages, the spend limit the owner has
approved on each, the budgets Njord draws from those limits, and the ledger of
every payment. It is a Brightside adapter written to move into Covia, and
nothing in it belongs to any one network. This doc is ahead of the code: today
Njord signs through Covia's Convex operations directly
([NJORD.md](NJORD.md)).

- **Generic, with rails beneath.** The treasury speaks of accounts, assets,
  amounts and payments. A rail is how one network does them; Convex is the
  first and the exemplar.
- **Limits are the owner's.** Each account has a spend limit: the total the
  owner has approved for spending from it. Only the owner raises it, through
  the Inbox.
- **Budgets are Njord's.** A budget is an allowance of one asset, drawn from
  an account's limit, which Njord creates, lowers and cancels on his own
  judgement. A grant on a budget is the power to spend from it.
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

## Accounts and rails

An account is one place funds are held on one rail, with whatever lets the
treasury spend from it, a key for a chain, held by reference in a custody the
treasury signs from. Networks, accounts and assets are named by the CAIP
standards that Convex's x402 support already uses for networks, so a name
means the same thing on every rail.

A rail is a `Rail`: how one network reads a balance and makes a payment. A
rail that fits an existing Covia operation delegates to it. The Convex rail
reads through `convex/query` and pays through `convex/transact`, so the key
is resolved by Covia from its reference, never seen by the treasury, and
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

A budget is an allowance: permission to draw up to an amount of one asset
from one account, for a purpose or for an agent. Creating one moves nothing
on the network. Njord creates, lowers and cancels budgets within what the
account's limit has left, and the treasury refuses a budget that would take
the account's budgets past it.

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

## Njord's place

Njord's bridge gains the treasury's operations and keeps Covia's raw
`transact` and `sign`, for what a payment cannot express. Those sign only
with keys he can reach, so an account's limit holds as long as its key is
not one of them. His judgement still decides each payment he makes; the
treasury decides only that it fits.

## New interfaces

| Interface | Who uses it |
|---|---|
| `brightside.treasury`, the adapter `treasury`, its state in Brightside's adapter state ([APP_STATE.md](APP_STATE.md)) | Brightside |
| `Rail`, and `ConvexRail` delegating to Covia's Convex operations | the treasury |
| Accounts: add an account by its key reference, list accounts with limit, drawn, budgets and balance | Njord |
| Limits: set a limit | the operator, on the owner's approval |
| Budgets: create, lower, cancel, list | Njord, with a grant on the account |
| Pay: from a budget, to a recipient, an amount, a reason | the holder of a grant on the budget |
| Ledger: read | Njord, Odin, the owner |
| Grant abilities: manage an account; spend from a budget | issued by Njord's bridge |
| An Inbox request kind for raising a limit, which Brightside applies on approval | Njord, the owner |

## Not yet decided

- Where the keys of the treasury's accounts are kept, so that Njord's raw
  signing cannot reach them, and how an account already held elsewhere
  comes under the treasury.
- How a contract call that spends, rather than a plain transfer, is paid
  through a rail.
- Whether budgets expire, and whether a limit may also renew each period.
- How an owner's approval runs a raise without Brightside applying it, once
  Covia has an ask that runs one call on approval (covia#592).
- Delegating budgets to assistants, once agents can present grants
  (covia#447).

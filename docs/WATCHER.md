# Watcher

How Brightside's screens, and the few things that must keep up off screen,
follow the venue's lattice: one shared watcher reads lattice components and
tells whoever asked when a value changes. It lives in `ui/binding/Watcher`.

- **A view wraps its domain component.** One call,
  `Watcher.watch(view, component, changed)`, keeps a view current with the
  Covia lattice component it shows; the view decides what to do with each new
  value.
- **What is not a view observes.** `Watcher.observe(read, changed)` keeps up
  from the call until it is closed, whatever is on screen, for things such as
  noticing that a request has arrived.
- **One thread serves everything.** Each pass reads every live watch, and the
  next pass starts a second after the last one finished, so a slow pass
  stretches the cycle instead of piling up.
- **Nothing off screen is read.** A view's watch follows Swing's own showing
  state, pauses while the window is minimised, and is checked again in every
  batch, so nothing stale keeps running.
- **No value, no call.** A component with no value, such as a record that has
  gone, leaves the view as it was; a failed read keeps the last value.

## Watching a component

A view hands the watcher the lattice component it shows, such as a job's
`JobRecord`, and a callback. The callback runs on the event thread, first when
the view comes on screen and then whenever the component's value changes; it is
the view's to decide what a change means, whether laying itself out again or
handing the value to a model. The two-argument form, `watch(view, component)`,
simply revalidates and repaints a view that reads its component as it paints.

The view holds the watch it gets back. It closes it when it is given another
component, such as the jobs of a different principal, and calls `refresh()` to
read at once rather than at the next pass, as after an action it took.

## Observing

Some things must keep up when nothing is on screen: a request that arrives
while the window is in the tray should still notify. `observe(read, changed)`
watches what `read` returns, which may combine several components, from the
call until the watch is closed. Its owner closes it when the reason for it
ends, as the app does for the Inbox on lock, principal switch and exit
([INBOX.md](INBOX.md)).

## A pass

One daemon thread does all the reading, so lattice reads never run on the event
thread. A pass reads the value of each live watch, compares it with the value
last handed over, which is cheap because values are immutable cells, and hands
every change to the event thread in one batch. Passes run with a fixed delay
between them rather than at a fixed rate: when a pass is slow, the next simply
starts later. With nothing live, a pass does nothing. A `refresh()` runs on the
same thread, so it never overlaps a pass.

## Lifetime

A view's watch joins the watcher when Swing says the view is showing and
leaves when it is not: when the page changes, the view is replaced, or the
window goes to the tray or closes. While the window is minimised the watch
stays but reads nothing. Every batch checks each watch's view again on the
event thread before handing anything over, so a Swing event that never arrived
costs at most one pass, and a view off screen is never told anything. Closing a
watch ends it for good and lets go of the view.

## Absent values and failures

A component bound to a place with nothing in it, such as a job whose record
has been deleted, has no value. The watcher calls nothing then, so the view
keeps what it last showed, and calls again if a value returns. A read that
fails keeps the last value, logs once until a read succeeds, and tells the view
nothing; there is no on-screen failure state.

## Testing

`WatcherTest` builds a watcher with no thread, drives each pass itself and
decides what counts as on screen and minimised, so the lifetime rules are
checked without a desktop or a clock. `JobListTest` checks that the component
handles a screen holds stay live as the venue changes.

## Known limits

- Changes are found by polling, so a view learns of one within about a second.
  Covia has no general lattice subscriptions yet; when it does, a watch can
  wait for a change instead of reading on every pass.

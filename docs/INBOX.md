# Inbox

How Brightside shows the decisions waiting for the owner, tells them when a new
one arrives and carries their answer back. Why a decision belongs to the owner
is in [DESIGN.md](DESIGN.md); how the gods ask is in
[GODS.md](GODS.md#asking-the-owner).

- **One composed source.** The Inbox shows the acting principal's own inbox
  and the venue's, where the gods ask, merged into one list.
- **Observed whatever is on screen.** The inbox is observed for as long as a
  principal is bound, so a request that arrives while the window is minimised
  or in the tray still notifies.
- **One projection feeds everything.** The cards, the Inbox badge, the mark on
  the tray icon and the notifications all come from the same snapshot.
- **Only what is new notifies.** Requests already waiting when the inbox is
  bound are a quiet starting point; each open request notifies once.
- **An answer needs a request that is still open.** It goes through the venue's
  HITL operation as the request's owner.

## Where requests come from

`Inbox.Source` composes Covia's typed Inbox components for the acting principal
and the venue; when Brightside acts as the venue's operator the two are one
inbox, read once. A request is known by its owner and id together, so the same
id in two inboxes stays two requests. Reading an account that does not exist
yet reads as absent and creates nothing, and a deleted account clears only its
own part of the list.

## Observing

`ui/inbox/InboxBinding` observes the source through the
[Watcher](WATCHER.md#observing) from the moment a principal is bound, and is
closed on lock, on a switch of principal, on restart and on exit. Each change
is projected once, on the event thread, into the requests the screen, the badge,
the tray and the notifications use. A read that fails keeps the last snapshot
and goes to the log.

## Showing

The Inbox screen renders its latest snapshot when it is shown, keeping
unchanged cards and the drafts in them by owner and request id; a switch of
principal discards those drafts. The badge counts the requests waiting for a
decision. While any are, the tray icon carries a small red dot, cleared when
none are left or on lock (`ui/TrayManager`). A newly arrived open request also
shows a notification from the tray.

## Answering

Only an open request in the current snapshot may supply the authority for an
answer, so a form left open on a request that has since changed asks for a
refresh instead of answering it. The answer goes through the venue's HITL
operation as the request's owner. Its completion belongs to the binding that
sent it, so it cannot update a different principal's view, and the inbox is
read again at once.

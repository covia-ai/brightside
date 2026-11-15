# Jobs

How *Settings → Jobs* shows the acting principal's jobs and acts on them,
with Covia's own lattice components as its domain model. What the page offers
the owner is described in [CONFIGURATION.md](CONFIGURATION.md#jobs); Covia's
job model is in [Covia Jobs](https://github.com/covia-ai/covia/blob/develop/venue/docs/JOBS.md).

- **Covia's components are the model.** The list wraps the principal's `Jobs`
  component, their job index, and the detail wraps one job's `JobRecord`; the
  [Watcher](WATCHER.md) keeps each current while it is on screen.
- **Time order comes free.** Covia keys the index by job id and ids rise with
  creation time, so a time range is a seek and a count, not a scan.
- **Only the rows on screen are built.** However long the history, the list
  projects a row's summary only when it is about to be drawn.
- **Actions go through Covia.** Pausing, resuming, cancelling and messaging use
  Covia's job operations as the principal, since only its job manager can stop
  running work.
- **Looking leaves no trace.** Reading the page submits nothing; each action
  leaves the job record it should.

## The list

The window hands the page the acting principal's `Jobs` component when a
principal is bound, and nothing on lock. The list watches it, and each new
index is filtered off the event thread into a view of row positions
(`JobList`).

- **When** is a seek: the id of the first job in range is the start time
  shifted into an id's high bits, so the rows run from that position to the
  newest.
- **How it stands** groups Covia's statuses: active, waiting for the owner,
  failed, completed and cancelled. The page offers one group at a time, or
  all.
- **Who started it** is read from the record's caller: the owner, one of their
  agents, or another principal acting for them, such as a god.

A filter on status or starter has to read records, so it scans the range from
the newest back; a time range alone reads none. The list is a `JList` over
`JobListModel`, which projects a row's `JobList.Job` only when the list asks
for it and keeps a few hundred recently asked for. A fixed cell size makes the
list ask only for the rows on screen. The selection follows its job by id
across new indexes.

## The detail

Opening a job builds a `JobDetail` around its `JobRecord`, which watches it and
lays itself out again whenever the record changes: status, actions, times, who
started it, the agent and its conversation, the job it ran inside, tokens, and
input, output, error and history in full. A breadcrumb leads back to the list
and through the jobs it ran inside.

Whether a job exists is decided when it is opened, from the jobs the list last
read: a link to one the principal does not hold shows it as not found. A job
deleted while its detail is open stays as it was, since a record that has gone
tells the view nothing, and leaves the list when the list next reads.

## Actions

The bar under the list, the right-click menu and the detail act on a job
through Covia's job operations as the acting principal (`JobList`), off the
event thread, and then read the list and any open detail again at once.

## Known limits

- The id order the time range relies on is how Covia makes ids, not yet a
  promise of its API
  ([covia#700](https://github.com/covia-ai/covia/issues/700)).
- A scheduled run's record does not say a schedule started it, so it counts as
  whoever scheduled it
  ([covia#701](https://github.com/covia-ai/covia/issues/701)).

# Feature Specification: Retry Delivery of Inventory Events That Failed to Publish (US-03)

**Feature Branch**: `feature/US-03-outbound-retry`

**Created**: 2026-11-14

**Status**: Draft

**Input**: User description: "US-03: Retry delivery of Inventory events that failed to publish
(outbound dead-letter retry worker). A background worker redelivers events stored in the outbound
dead-letter table when publishing failed after the commit, so no stock outcome is lost while Kafka is
unavailable. Reuse the existing table. Poll on an interval for due rows, claim them so several
instances never process the same row, republish the stored payload unchanged, mark Resolved on
success, back off exponentially on failure, mark Exhausted after a maximum number of attempts. Never
block or crash the service; graceful shutdown; configurable settings documented in the README; batch
and failure logging. Out of scope: manual replay API, alerting, admin UI, retry of inbound dead
letters."

## Context

When Inventory decides a reservation (US-01) or releases one (US-02), it saves the decision and then
publishes the outcome: `inventory.stock-reserved`, `inventory.stock-rejected` or
`inventory.stock-released`. If publishing fails after the save (the message broker is down, for
example), the decision stays saved and the complete outcome message is stored as an **outbound dead
letter**: status Pending, retry count 0, a next-retry time and the last error (ADR 0004). Today nothing
ever sends those messages: Orders would wait for an outcome that never arrives, and the order saga
would stall.

This feature adds the missing half: a background worker that keeps trying to deliver those stored
outcomes until they are delivered or a limit is reached. It reuses the existing outbound dead-letter
store; there is no second delivery mechanism.

## User Scenarios & Testing *(mandatory)*

The "users" are the services waiting for Inventory's outcomes (Orders, Notifications) and the
operators who run Inventory.

### User Story 1 - Deliver outcomes that failed to publish once the broker is back (Priority: P1)

An outcome that could not be published is delivered automatically later, exactly as it was first
produced, so the order saga continues without anyone intervening.

**Why this priority**: This is the whole value of the feature: without it a stored outcome is never
delivered and its order is stuck.

**Independent Test**: Store an outcome as a Pending outbound dead letter that is due, with the broker
reachable; within one polling interval the message appears on its topic with the same key, event id
and payload, and the entry is marked Resolved with the time it was resolved.

**Acceptance Scenarios**:

1. **Given** a Pending outbound dead letter whose next-retry time has passed, **When** the worker
   runs, **Then** the stored message is published to the stored topic with the stored key and the
   exact stored payload (same event id), and the entry becomes Resolved with its resolution time set.
2. **Given** several due Pending entries for different topics (reserved, rejected, released),
   **When** the worker runs, **Then** each is published to its own topic and marked Resolved.
3. **Given** a Pending entry whose next-retry time is still in the future, **When** the worker runs,
   **Then** it is not published and stays Pending, unchanged.
4. **Given** a Resolved or Exhausted entry, **When** the worker runs, **Then** it is never published
   again.

---

### User Story 2 - Back off and give up gracefully while the broker stays down (Priority: P1)

While the broker is unavailable, each delivery attempt fails, and the next attempt is pushed further
into the future. After a maximum number of attempts the entry is marked Exhausted, reported loudly in
the logs and kept for a person to inspect. The service keeps running normally the whole time.

**Why this priority**: A worker that retries in a tight loop or crashes the service during an outage
would turn one failure into many. Backing off and stopping is as important as delivering.

**Independent Test**: With the broker unreachable, let the worker attempt a due entry several times;
verify the retry count grows by one per attempt, each next-retry time is later by the backoff rule,
the last error is stored, and after the maximum attempts the entry is Exhausted and logged at error
level, while the service stays healthy.

**Acceptance Scenarios**:

1. **Given** a due Pending entry and an unreachable broker, **When** the worker attempts it,
   **Then** the retry count increases by one, the last error is recorded, the status stays Pending,
   and the next-retry time becomes now plus the backoff delay for that attempt.
2. **Given** base delay 30 s, multiplier 2 and maximum delay 15 min, **When** attempts 1, 2, 3, 4, 5
   and 6 fail, **Then** the next attempts are scheduled 30 s, 1 min, 2 min, 4 min, 8 min and 15 min
   later (the delay never exceeds the maximum).
3. **Given** an entry that has reached the maximum number of attempts, **When** its last attempt
   fails, **Then** it becomes Exhausted, an error-level log line records its id, event id, topic, key,
   number of attempts and last error, and it is never attempted again; the entry and its payload are
   kept.
4. **Given** the broker is unreachable, **When** the service starts and runs, **Then** it starts, its
   health check reports healthy, and consuming and handling orders and cancellations continues;
   failing delivery attempts never stop or slow other work.
5. **Given** the broker becomes reachable again, **When** the next attempt of a Pending entry is due,
   **Then** it is delivered and Resolved (Story 1).

---

### User Story 3 - Run safely on several instances at once (Priority: P1)

Several instances of the service can run the worker at the same time. Each due entry is processed by
only one of them per round, so outcomes are not published many times over and attempt counts stay
correct.

**Why this priority**: The service is expected to run with more than one instance; without this, every
instance would republish every entry and corrupt the retry counts.

**Independent Test**: Run several workers at the same moment against the same set of due entries;
verify each entry is attempted exactly once in that round and each delivered entry is published once.

**Acceptance Scenarios**:

1. **Given** 50 due entries and three workers polling at the same moment, **When** the round
   completes, **Then** every entry was attempted by exactly one worker, each delivered entry was
   published once, and each retry count increased by at most one.
2. **Given** a worker is processing an entry, **When** another worker polls, **Then** it skips that
   entry without waiting for it.

---

### User Story 4 - Stop cleanly and keep the operator informed (Priority: P2)

When the service shuts down, the worker finishes or releases what it is doing and leaves everything
else untouched. Operators can follow what the worker does from its logs and tune it from
configuration.

**Why this priority**: Needed for safe deployments and day-to-day operation, but it does not change
what gets delivered.

**Independent Test**: Stop the service while a batch is in progress; verify every entry of the batch
is either Resolved (delivered) or still Pending with its previous values, none is lost or half
updated, and entries not yet taken are untouched. Check that each batch and each failure produce the
documented log lines, and that changing a setting changes the behaviour.

**Acceptance Scenarios**:

1. **Given** a batch in progress, **When** the service is asked to shut down, **Then** the worker
   stops taking new batches, the entry being sent is completed (Resolved or recorded as a failed
   attempt) or released unchanged, the remaining entries of the batch are released unchanged, and
   shutdown is not delayed beyond the configured publish timeout plus 5 seconds.
2. **Given** a round that took entries, **When** it ends, **Then** one log line reports how many
   entries were taken, resolved, failed and exhausted.
3. **Given** an attempt fails, **When** it is recorded, **Then** one log line reports the entry id,
   topic, attempt number and next attempt time.
4. **Given** a round finds no due entries, **When** it ends, **Then** nothing is logged above debug
   level (no noise when idle).
5. **Given** the settings are changed through environment variables, **When** the service starts,
   **Then** the worker uses them; the README lists every setting with its default.

---

### Edge Cases

- **An event that was Resolved fails to publish again later** (for example, a redelivered order or
  cancellation republishes its stored outcome while the broker is down): the existing entry for that
  event becomes Pending and due again with the new error, instead of staying Resolved and never being
  retried; its attempt count restarts.
- **The same event fails again while its entry is still Pending**: the entry is not duplicated; its last
  error is updated and its schedule is kept.
- **The same event fails again after its entry was Exhausted**: it stays Exhausted (manual inspection),
  with the new error recorded.
- **A redelivered order or cancellation fails to republish its outcome at the moment the worker is
  sending that same entry**: recording the new failure waits until the worker has finished with the
  entry, at most one publish timeout. This is the only case where the worker can make message handling
  wait; it is rare (same event, same moment, broker failing) and bounded, and is accepted.
- **The same outcome is published twice** (a worker delivered it, and a redelivery also republished it,
  or a crash happened after publishing but before marking it Resolved): consumers receive the same
  event id twice and discard the duplicate (at-least-once delivery).
- **The broker accepts the message but the service stops before the entry is marked Resolved**: the
  entry is attempted again later and delivered a second time with the same event id; nothing is lost.
- **A batch is larger than what can be sent before the next poll**: the next round only takes entries
  that are not already being processed; rounds never overlap on the same instance.
- **Clock differences between instances**: due-ness is decided by the database time, so instances agree
  on which entries are due.
- **Many due entries after a long outage**: they are drained in batches of the configured size, oldest
  due first, without starving new entries.
- **A stored payload that the broker rejects permanently** (for example, too large): it is retried like
  any other failure and ends Exhausted with the error recorded; it never blocks other entries.
- **Maximum attempts set to 0 or a negative value, or other invalid settings**: the service refuses to
  start with a clear configuration error.

## Requirements *(mandatory)*

### Functional Requirements

**Selecting and claiming work**

- **FR-001**: The system MUST run a background worker that, on a configurable interval, looks for
  outbound dead-letter entries that are Pending and whose next-retry time is not in the future.
- **FR-002**: Each round MUST take at most the configured batch size of due entries, oldest next-retry
  time first.
- **FR-003**: Entries taken by one worker MUST NOT be taken by another worker (on the same or another
  instance) until the first one has finished with them; a worker MUST skip entries that are being
  processed elsewhere instead of waiting for them.
- **FR-004**: Rounds on the same instance MUST NOT overlap.

**Delivering**

- **FR-005**: The system MUST publish each taken entry's stored payload, unchanged, to its stored topic
  with its stored message key, so the event id is the one originally produced.
- **FR-006**: On success the entry MUST become Resolved and record when it was resolved; it MUST never
  be published by the worker again.
- **FR-007**: Delivery is at-least-once: an entry MAY be published more than once (for example after a
  crash between publishing and recording the success), but always with the same event id and payload.

**Failures, backoff and exhaustion**

- **FR-008**: On a failed attempt the system MUST increase the entry's attempt count by one, store the
  error of that attempt, and set the next-retry time to now plus the backoff delay.
- **FR-009**: The backoff delay for attempt *n* (n ≥ 1) MUST be `base delay × multiplier^(n−1)`, never
  more than the maximum delay. Base delay, multiplier and maximum delay are configurable.
- **FR-010**: When an attempt fails and the entry has reached the configured maximum number of
  attempts, the entry MUST become Exhausted, MUST be logged at error level with its id, event id, topic,
  key, attempt count and last error, and MUST NOT be attempted again. Exhausted entries and their
  payloads MUST be kept.
- **FR-011**: Every change to an entry (resolved, failed attempt, exhausted) MUST be saved atomically
  for that entry; an entry MUST never be left half updated.
- **FR-012**: When an event that already has an entry fails to publish again outside the worker (US-01
  and US-02 republishing a stored outcome): a Resolved entry MUST become Pending and due again with the
  new error and its attempt count reset; a Pending entry MUST keep its schedule and only record the new
  error; an Exhausted entry MUST stay Exhausted and record the new error. Exactly one entry MUST exist
  per event.

**Resilience and lifecycle**

- **FR-013**: The worker MUST NOT stop, block or slow the consumption and handling of orders and
  cancellations, and MUST NOT crash the service; an unexpected error in a round MUST be logged and the
  next round MUST still run.
- **FR-014**: The service MUST start and report healthy when the broker is unreachable; the worker then
  only records failed attempts.
- **FR-015**: On shutdown the worker MUST stop starting rounds, MUST complete or release the entry being
  sent, MUST release the rest of the batch unchanged, and MUST NOT delay shutdown longer than the
  configured publish timeout plus a 5-second margin.

**Configuration and observability**

- **FR-016**: The interval, batch size, maximum attempts, base delay, multiplier and maximum delay MUST
  be configurable with environment-variable overrides and these local defaults: interval 30 s, batch
  size 50, maximum attempts 10, base delay 30 s, multiplier 2, maximum delay 1 h. Invalid values MUST
  prevent the service from starting with a clear message. The README MUST document every setting.
- **FR-017**: Each round that took at least one entry MUST log one line with the counts of entries
  taken, resolved, failed and exhausted. Each failed attempt MUST log one line with the entry id, topic,
  attempt number and next attempt time. Idle rounds MUST NOT log above debug level.

**Out of scope**

- **FR-018**: This feature MUST NOT add a manual replay API, alerting, an admin UI, or retries of
  inbound (consumed-message) dead letters.

### Key Entities

- **Outbound dead-letter entry** (existing, from US-01/US-02): event id (unique), topic, message key,
  full payload, status (Pending, Resolved, Exhausted), attempt count, next-retry time, last error,
  created and updated times. Gains a **resolved-at** time, set when it becomes Resolved.
- **Retry policy** (configuration): interval, batch size, maximum attempts, base delay, multiplier,
  maximum delay.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After the broker becomes reachable again, 100% of due Pending entries are delivered and
  Resolved within one polling interval plus the time to send them (with defaults: within about 30 s
  for a batch of up to 50).
- **SC-002**: During an outage, 0 entries are attempted more often than the backoff schedule allows,
  and every entry reaches Exhausted after exactly the configured maximum number of attempts.
- **SC-003**: With three instances polling the same 50 due entries at the same time, each entry is
  attempted exactly once per round and each delivered entry is published exactly once.
- **SC-004**: 100% of delivered messages carry the same event id, key and payload as the stored entry.
- **SC-005**: While the broker is down, the service starts in the same time as without the worker,
  reports healthy, and keeps handling orders and cancellations at its normal rate.
- **SC-006**: Shutting down during a batch leaves 0 entries half updated and 0 entries lost.
- **SC-007**: An operator can tell from the logs alone, for any round, how many entries were taken,
  resolved, failed and exhausted, and for any failure, when its next attempt is.

## Assumptions

- "PendingRetry" in the request is the existing **Pending** status of outbound dead-letter entries; the
  status values Pending, Resolved and Exhausted already exist and are not renamed.
- The attempt count stored on the entry counts worker attempts; the failed first publish that created
  the entry is not counted. With maximum attempts 10, an entry is tried 10 times by the worker before it
  becomes Exhausted.
- The first worker attempt happens at the next-retry time set when the entry was created (US-01/US-02
  default: 30 s after the failure).
- A failed attempt has a bounded duration (the existing publish timeout, 15 s by default), so one
  unreachable broker cannot hold a round forever.
- Entries are kept indefinitely; cleaning up old Resolved or Exhausted entries is a later concern.
- Exhausted entries are recovered by a person (for example by setting them back to Pending in the
  database); a tool for that is out of scope.
- Consumers deduplicate by event id, as the shared event contract requires; no contract change is needed
  and `ecommerce-infra/docs/events.md` does not need to change.

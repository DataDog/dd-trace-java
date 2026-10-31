---------------------- MODULE TelemetryRetryOwnership -----------------------
EXTENDS Naturals

(**
 * Models TelemetryService moving one accepted event from its source queue to
 * BufferedEvents for one retry. A second failed attempt is an explicit bounded
 * retry-policy drop, matching the current implementation.
 *)

VARIABLES queued, requestOwns, buffered, delivered, policyDropped, attempts, pc

vars == <<queued, requestOwns, buffered, delivered, policyDropped, attempts, pc>>

Init ==
  /\ queued = TRUE
  /\ requestOwns = FALSE
  /\ buffered = FALSE
  /\ delivered = FALSE
  /\ policyDropped = FALSE
  /\ attempts = 0
  /\ pc = "Accepted"

BuildFirstRequest ==
  /\ pc = "Accepted"
  /\ queued' = FALSE
  /\ requestOwns' = TRUE
  /\ buffered' = TRUE
  /\ attempts' = 1
  /\ pc' = "FirstInFlight"
  /\ UNCHANGED <<delivered, policyDropped>>

FirstSucceeds ==
  /\ pc = "FirstInFlight"
  /\ requestOwns' = FALSE
  /\ buffered' = FALSE
  /\ delivered' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED <<queued, policyDropped, attempts>>

FirstFails ==
  /\ pc = "FirstInFlight"
  /\ requestOwns' = FALSE
  /\ pc' = "RetryBuffered"
  /\ UNCHANGED <<queued, buffered, delivered, policyDropped, attempts>>

BuildRetry ==
  /\ pc = "RetryBuffered"
  /\ buffered' = FALSE
  /\ requestOwns' = TRUE
  /\ attempts' = 2
  /\ pc' = "RetryInFlight"
  /\ UNCHANGED <<queued, delivered, policyDropped>>

RetrySucceeds ==
  /\ pc = "RetryInFlight"
  /\ requestOwns' = FALSE
  /\ delivered' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED <<queued, buffered, policyDropped, attempts>>

RetryFails ==
  /\ pc = "RetryInFlight"
  /\ requestOwns' = FALSE
  /\ policyDropped' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED <<queued, buffered, delivered, attempts>>

Next ==
  BuildFirstRequest \/ FirstSucceeds \/ FirstFails \/ BuildRetry \/
    RetrySucceeds \/ RetryFails

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ queued \in BOOLEAN
  /\ requestOwns \in BOOLEAN
  /\ buffered \in BOOLEAN
  /\ delivered \in BOOLEAN
  /\ policyDropped \in BOOLEAN
  /\ attempts \in 0..2
  /\ pc \in {"Accepted", "FirstInFlight", "RetryBuffered", "RetryInFlight", "Done"}

AcceptedEventHasOwnerOrTerminal ==
  queued \/ requestOwns \/ buffered \/ delivered \/ policyDropped

TerminalStateIsExclusive ==
  (delivered \/ policyDropped) =>
    (~queued /\ ~requestOwns /\ ~buffered /\ ~(delivered /\ policyDropped))

=============================================================================

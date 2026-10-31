-------------------------- MODULE DataStreamsClose --------------------------

(**
 * Models DefaultDataStreamsMonitoring.close with a full bounded inbox. The
 * non-blocking offer of POISON_PILL fails, join times out, and close returns
 * while the worker remains alive without a future stop signal.
 *)

VARIABLES inboxFull, poisonQueued, workerAlive, closeReturned, pc

vars == <<inboxFull, poisonQueued, workerAlive, closeReturned, pc>>

Init ==
  /\ inboxFull = TRUE
  /\ poisonQueued = FALSE
  /\ workerAlive = TRUE
  /\ closeReturned = FALSE
  /\ pc = "WorkerBlocked"

OfferPoison ==
  /\ pc = "WorkerBlocked"
  /\ poisonQueued' = IF inboxFull THEN FALSE ELSE TRUE
  /\ pc' = "Joining"
  /\ UNCHANGED <<inboxFull, workerAlive, closeReturned>>

JoinTimesOut ==
  /\ pc = "Joining"
  /\ ~poisonQueued
  /\ closeReturned' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED <<inboxFull, poisonQueued, workerAlive>>

Next == OfferPoison \/ JoinTimesOut

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ inboxFull \in BOOLEAN
  /\ poisonQueued \in BOOLEAN
  /\ workerAlive \in BOOLEAN
  /\ closeReturned \in BOOLEAN
  /\ pc \in {"WorkerBlocked", "Joining", "Done"}

CloseReturnsOnlyAfterWorkerStops == closeReturned => ~workerAlive

=============================================================================

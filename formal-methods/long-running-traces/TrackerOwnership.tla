-------------------------- MODULE TrackerOwnership --------------------------
EXTENDS Naturals

(**
 * Ownership model for the hand-off from PendingTraceBuffer.Worker to
 * LongRunningTracesTracker. A live trace must always have an owner that can
 * eventually write it: the queue, worker, tracker, or fallback buffer path.
 *)

CONSTANT Capacity

ASSUME Capacity \in Nat \ {0}

VARIABLES
  queueOwns,
  workerOwns,
  trackerOwns,
  fallbackOwns,
  trackerSize,
  traceState,
  addResult,
  pc

vars ==
  <<queueOwns, workerOwns, trackerOwns, fallbackOwns, trackerSize,
    traceState, addResult, pc>>

Init ==
  /\ queueOwns = TRUE
  /\ workerOwns = FALSE
  /\ trackerOwns = FALSE
  /\ fallbackOwns = FALSE
  /\ trackerSize = Capacity
  /\ traceState = "TO_TRACK"
  /\ addResult = FALSE
  /\ pc = "Queued"

Dequeue ==
  /\ pc = "Queued"
  /\ queueOwns' = FALSE
  /\ workerOwns' = TRUE
  /\ pc' = "Adding"
  /\ UNCHANGED
       <<trackerOwns, fallbackOwns, trackerSize, traceState, addResult>>

(**
 * Current LongRunningTracesTracker.add behavior: the state transition succeeds
 * and add returns true even when addTrace drops the trace at capacity.
 *)
TrackerAdd ==
  /\ pc = "Adding"
  /\ traceState = "TO_TRACK"
  /\ traceState' = "TRACKED"
  /\ addResult' = TRUE
  /\ IF trackerSize = Capacity
        THEN /\ UNCHANGED <<trackerOwns, trackerSize>>
        ELSE /\ trackerOwns' = TRUE
             /\ trackerSize' = trackerSize + 1
  /\ pc' = "AfterAdd"
  /\ UNCHANGED <<queueOwns, workerOwns, fallbackOwns>>

TrackerAccepted ==
  /\ pc = "AfterAdd"
  /\ addResult
  /\ workerOwns' = FALSE
  /\ pc' = "Done"
  /\ UNCHANGED
       <<queueOwns, trackerOwns, fallbackOwns, trackerSize, traceState,
         addResult>>

TrackerRejected ==
  /\ pc = "AfterAdd"
  /\ ~addResult
  /\ workerOwns' = FALSE
  /\ fallbackOwns' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED
       <<queueOwns, trackerOwns, trackerSize, traceState, addResult>>

Next == Dequeue \/ TrackerAdd \/ TrackerAccepted \/ TrackerRejected

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ queueOwns \in BOOLEAN
  /\ workerOwns \in BOOLEAN
  /\ trackerOwns \in BOOLEAN
  /\ fallbackOwns \in BOOLEAN
  /\ trackerSize \in Nat
  /\ traceState \in {"TO_TRACK", "TRACKED", "NOT_TRACKED"}
  /\ addResult \in BOOLEAN
  /\ pc \in {"Queued", "Adding", "AfterAdd", "Done"}

LiveTraceHasOwner ==
  queueOwns \/ workerOwns \/ trackerOwns \/ fallbackOwns

=============================================================================

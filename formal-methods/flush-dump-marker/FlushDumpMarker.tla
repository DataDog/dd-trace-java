--------------------------- MODULE FlushDumpMarker ---------------------------

(**
 * Models a tracer-flare dump command queued behind a flush command while the
 * PendingTraceBuffer worker is busy. Processing the flush drains the dump
 * command through CommandElement.write(), which is a no-op, so the waiter
 * loses both the queued command and its acknowledgement.
 *)

VARIABLES
  flushQueued,
  dumpQueued,
  flushAcknowledged,
  dumpAcknowledged,
  dumpWaiting,
  pc

vars ==
  <<flushQueued, dumpQueued, flushAcknowledged, dumpAcknowledged, dumpWaiting, pc>>

Init ==
  /\ flushQueued = FALSE
  /\ dumpQueued = FALSE
  /\ flushAcknowledged = FALSE
  /\ dumpAcknowledged = FALSE
  /\ dumpWaiting = FALSE
  /\ pc = "WorkerBlocked"

QueueFlush ==
  /\ pc = "WorkerBlocked"
  /\ flushQueued' = TRUE
  /\ pc' = "FlushQueued"
  /\ UNCHANGED <<dumpQueued, flushAcknowledged, dumpAcknowledged, dumpWaiting>>

QueueDump ==
  /\ pc = "FlushQueued"
  /\ dumpQueued' = TRUE
  /\ dumpWaiting' = TRUE
  /\ pc' = "DumpQueued"
  /\ UNCHANGED <<flushQueued, flushAcknowledged, dumpAcknowledged>>

(** Current behavior: WriteDrain consumes the dump marker without acknowledging it. *)
FlushDrainsDump ==
  /\ pc = "DumpQueued"
  /\ flushQueued' = FALSE
  /\ dumpQueued' = FALSE
  /\ flushAcknowledged' = TRUE
  /\ pc' = "DumpMarkerLost"
  /\ UNCHANGED <<dumpAcknowledged, dumpWaiting>>

Next == QueueFlush \/ QueueDump \/ FlushDrainsDump

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ flushQueued \in BOOLEAN
  /\ dumpQueued \in BOOLEAN
  /\ flushAcknowledged \in BOOLEAN
  /\ dumpAcknowledged \in BOOLEAN
  /\ dumpWaiting \in BOOLEAN
  /\ pc \in {"WorkerBlocked", "FlushQueued", "DumpQueued", "DumpMarkerLost"}

WaitingDumpHasOwner ==
  dumpWaiting => (dumpQueued \/ dumpAcknowledged)

=============================================================================

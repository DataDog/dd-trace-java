------------------------- MODULE FlushDrainOwnership -------------------------
EXTENDS Naturals

(**
 * Models a PendingTrace queued behind FLUSH_ELEMENT while the worker is busy.
 * The flush drain writes the trace but, unlike normal dequeue, does not clear
 * its isEnqueued flag. A later child finish then cannot requeue the trace.
 *)

VARIABLES
  queueOwns,
  enqueued,
  rootWritten,
  lateChildPending,
  pc

vars == <<queueOwns, enqueued, rootWritten, lateChildPending, pc>>

Init ==
  /\ queueOwns = TRUE
  /\ enqueued = TRUE
  /\ rootWritten = FALSE
  /\ lateChildPending = FALSE
  /\ pc = "QueuedBehindFlush"

(** Current WriteDrain.accept behavior: write without setEnqueued(false). *)
FlushDrainWrites ==
  /\ pc = "QueuedBehindFlush"
  /\ queueOwns' = FALSE
  /\ rootWritten' = TRUE
  /\ pc' = "Flushed"
  /\ UNCHANGED <<enqueued, lateChildPending>>

LateChildFinishes ==
  /\ pc = "Flushed"
  /\ lateChildPending' = TRUE
  /\ IF enqueued
        THEN /\ UNCHANGED <<queueOwns, enqueued>>
        ELSE /\ queueOwns' = TRUE
             /\ enqueued' = TRUE
  /\ pc' = "Done"
  /\ UNCHANGED rootWritten

Next == FlushDrainWrites \/ LateChildFinishes

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ queueOwns \in BOOLEAN
  /\ enqueued \in BOOLEAN
  /\ rootWritten \in BOOLEAN
  /\ lateChildPending \in BOOLEAN
  /\ pc \in {"QueuedBehindFlush", "Flushed", "Done"}

PendingChildHasWriter ==
  lateChildPending => queueOwns

=============================================================================

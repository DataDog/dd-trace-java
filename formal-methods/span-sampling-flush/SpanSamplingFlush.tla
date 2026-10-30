-------------------------- MODULE SpanSamplingFlush --------------------------

(**
 * Models an accepted trace moving through the single-span-sampling worker
 * before it reaches the serializer. TraceProcessingWorker.flush places its
 * barrier directly in the serializer queue, so that barrier can complete
 * while an older accepted trace is still in the upstream sampling stage.
 *)

VARIABLES
  samplingQueued,
  samplingInFlight,
  serializerQueued,
  serialized,
  flushRequested,
  flushAcknowledged,
  pc

vars ==
  <<samplingQueued, samplingInFlight, serializerQueued, serialized,
    flushRequested, flushAcknowledged, pc>>

Init ==
  /\ samplingQueued = FALSE
  /\ samplingInFlight = FALSE
  /\ serializerQueued = FALSE
  /\ serialized = FALSE
  /\ flushRequested = FALSE
  /\ flushAcknowledged = FALSE
  /\ pc = "Ready"

AcceptCandidate ==
  /\ pc = "Ready"
  /\ samplingQueued' = TRUE
  /\ pc' = "SamplingQueued"
  /\ UNCHANGED <<samplingInFlight, serializerQueued, serialized,
                  flushRequested, flushAcknowledged>>

StartSampling ==
  /\ pc = "SamplingQueued"
  /\ samplingQueued' = FALSE
  /\ samplingInFlight' = TRUE
  /\ pc' = "SamplingInFlight"
  /\ UNCHANGED <<serializerQueued, serialized, flushRequested, flushAcknowledged>>

RequestFlush ==
  /\ pc = "SamplingInFlight"
  /\ flushRequested' = TRUE
  /\ pc' = "FlushRequested"
  /\ UNCHANGED <<samplingQueued, samplingInFlight, serializerQueued,
                  serialized, flushAcknowledged>>

(** Current behavior: the serializer barrier does not wait for the sampling stage. *)
AcknowledgeSerializerFlush ==
  /\ pc = "FlushRequested"
  /\ flushAcknowledged' = TRUE
  /\ pc' = "FlushReturned"
  /\ UNCHANGED <<samplingQueued, samplingInFlight, serializerQueued,
                  serialized, flushRequested>>

Next ==
  AcceptCandidate \/ StartSampling \/ RequestFlush \/ AcknowledgeSerializerFlush

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ samplingQueued \in BOOLEAN
  /\ samplingInFlight \in BOOLEAN
  /\ serializerQueued \in BOOLEAN
  /\ serialized \in BOOLEAN
  /\ flushRequested \in BOOLEAN
  /\ flushAcknowledged \in BOOLEAN
  /\ pc \in {"Ready", "SamplingQueued", "SamplingInFlight",
              "FlushRequested", "FlushReturned"}

FlushCoversAcceptedCandidate ==
  flushAcknowledged => serialized

=============================================================================

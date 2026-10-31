------------------------- MODULE BarrierContracts --------------------------

(** Reusable predicates for flush, close, and command acknowledgement. *)

FlushCoversAccepted(accepted, delivered, explicitlyDropped, acknowledged) ==
  acknowledged => (~accepted \/ delivered \/ explicitlyDropped)

CloseStopsWorkers(closeReturned, workersRunning) ==
  closeReturned => ~workersRunning

WaitingCommandHasOwner(waiting, queued, processing, acknowledged, cancelled) ==
  waiting => (queued \/ processing \/ acknowledged \/ cancelled)

=============================================================================

------------------------ MODULE OwnershipContracts -------------------------
EXTENDS FiniteSets, Naturals

(** Reusable predicates for work that moves between asynchronous components. *)

ActiveOwnerCount(queued, processing, retained) ==
  Cardinality({owner \in {"queued", "processing", "retained"} :
    CASE owner = "queued" -> queued
      [] owner = "processing" -> processing
      [] owner = "retained" -> retained})

HasOwnerOrTerminal(queued, processing, retained, delivered, dropped) ==
  queued \/ processing \/ retained \/ delivered \/ dropped

AtMostOneActiveOwner(queued, processing, retained) ==
  ActiveOwnerCount(queued, processing, retained) <= 1

TerminalIsExclusive(queued, processing, retained, delivered, dropped) ==
  (delivered \/ dropped) =>
    (~queued /\ ~processing /\ ~retained /\ ~(delivered /\ dropped))

=============================================================================

------------------------ MODULE ListenerDistribution ------------------------

(**
 * Models ProductState.callListenerApplyTarget when the first product listener
 * throws a generic exception. The exception handler caches the target and the
 * next identical response is treated as unchanged, so later healthy listeners
 * never receive it.
 *)

VARIABLES
  firstListenerCalled,
  healthyListenerCalled,
  targetCached,
  retryPending,
  pc

vars ==
  <<firstListenerCalled, healthyListenerCalled, targetCached, retryPending, pc>>

Init ==
  /\ firstListenerCalled = FALSE
  /\ healthyListenerCalled = FALSE
  /\ targetCached = FALSE
  /\ retryPending = TRUE
  /\ pc = "ResponseReceived"

FirstListenerThrows ==
  /\ pc = "ResponseReceived"
  /\ firstListenerCalled' = TRUE
  /\ pc' = "HandlingFailure"
  /\ UNCHANGED <<healthyListenerCalled, targetCached, retryPending>>

(** Current generic-exception path calls updateConfigState and caches the hash. *)
CacheFailedTarget ==
  /\ pc = "HandlingFailure"
  /\ targetCached' = TRUE
  /\ retryPending' = FALSE
  /\ pc' = "Cached"
  /\ UNCHANGED <<firstListenerCalled, healthyListenerCalled>>

ReplaySameResponse ==
  /\ pc = "Cached"
  /\ pc' = "Done"
  /\ UNCHANGED <<firstListenerCalled, healthyListenerCalled, targetCached, retryPending>>

Next == FirstListenerThrows \/ CacheFailedTarget \/ ReplaySameResponse

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ firstListenerCalled \in BOOLEAN
  /\ healthyListenerCalled \in BOOLEAN
  /\ targetCached \in BOOLEAN
  /\ retryPending \in BOOLEAN
  /\ pc \in {"ResponseReceived", "HandlingFailure", "Cached", "Done"}

CachedTargetIsFullyDistributed ==
  targetCached => (healthyListenerCalled \/ retryPending)

=============================================================================

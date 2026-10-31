------------------------ MODULE ContinuationLifecycle ------------------------
EXTENDS Integers, Naturals, FiniteSets

(**
 * Abstract lifecycle shared by ScopeContinuation and
 * ThreadLocalContextManager.ContextContinuationImpl.
 *)

CONSTANTS Threads, MaxResumes

ASSUME /\ Threads # {}
       /\ MaxResumes \in Nat \ {0}

ScopeTokens == Threads \X (1..MaxResumes)

VARIABLES held, terminal, openScopes, finalizations

vars == <<held, terminal, openScopes, finalizations>>

Init ==
  /\ held = FALSE
  /\ terminal = FALSE
  /\ openScopes = {}
  /\ finalizations = 0

Hold ==
  /\ ~terminal
  /\ ~held
  /\ openScopes = {}
  /\ held' = TRUE
  /\ UNCHANGED <<terminal, openScopes, finalizations>>

Resume(thread, slot) ==
  LET token == <<thread, slot>> IN
    /\ ~terminal
    /\ token \notin openScopes
    /\ openScopes' = openScopes \cup {token}
    /\ UNCHANGED <<held, terminal, finalizations>>

Close(thread, slot) ==
  LET token == <<thread, slot>>
      remaining == openScopes \ {token}
  IN
    /\ token \in openScopes
    /\ openScopes' = remaining
    /\ IF ~held /\ remaining = {}
          THEN /\ terminal' = TRUE
               /\ finalizations' = finalizations + 1
          ELSE /\ UNCHANGED <<terminal, finalizations>>
    /\ UNCHANGED held

Release ==
  /\ ~terminal
  /\ (held \/ openScopes = {})
  /\ held' = FALSE
  /\ IF openScopes = {}
        THEN /\ terminal' = TRUE
             /\ finalizations' = finalizations + 1
        ELSE /\ UNCHANGED <<terminal, finalizations>>
  /\ UNCHANGED openScopes

Next ==
  Hold \/ Release \/
    \E thread \in Threads, slot \in 1..MaxResumes:
      Resume(thread, slot) \/ Close(thread, slot)

Spec == Init /\ [][Next]_vars

TypeOK ==
  /\ held \in BOOLEAN
  /\ terminal \in BOOLEAN
  /\ openScopes \subseteq ScopeTokens
  /\ finalizations \in Nat

FinalizedAtMostOnce == finalizations <= 1

TerminalIsQuiescent == terminal => (openScopes = {} /\ ~held)

FinalizedIffTerminal == (finalizations = 1) <=> terminal

=============================================================================

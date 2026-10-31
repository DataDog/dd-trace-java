# Continuation lifecycle

Models the abstract hold, resume, scope-close, and release protocol shared by
the context continuation implementations. Within the configured bounds, TLC
checks that finalization occurs at most once and only after all holds and
resumed scopes are gone.

This is a passing executable contract. A CAS-level refinement can be added
without changing the higher-level invariants.

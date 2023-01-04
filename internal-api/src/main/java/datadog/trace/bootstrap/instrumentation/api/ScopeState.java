package datadog.trace.bootstrap.instrumentation.api;

/** Encapsulates the state of the scopes to be activated/restored on a particular thread. */
public interface ScopeState {
  /**
   * Activate the states encapsulated in this scope state on the current thread, and store the
   * currently active scopes, so they can be restored later.
   */
  void activate();

  /** Restore the saved scopes and save the active ones in this scope state. */
  void restore();

  ScopeState NO_OP = new NoopScopeState();

  final class NoopScopeState implements ScopeState {
    @Override
    public void activate() {}

    @Override
    public void restore() {}
  }
}

package datadog.trace.agent.test.scopediag;

/**
 * A scope or continuation lifecycle condition evaluated by the test diagnostic. Enforcement or
 * advisory treatment is defined independently by the diagnostic policy.
 */
public enum ScopeDiagnosticsCheck {
  /** Continuation captured but never resolved within the window. */
  LEAKED,
  /** Continuation resolved/resumed after the root span of its trace was already written. */
  LATE_FINISH,
  /** Continuation resolved more than once. */
  DOUBLE_FINISH,
  /** Continuation activated after it had already been resolved. */
  ACTIVATE_AFTER_RESOLVE,
  /** Scope closed while not on top of its thread's stack (closed on the wrong thread / order). */
  CLOSE_WRONG_THREAD,
  /** Scope opened but never closed within the window. */
  NEVER_CLOSED
}

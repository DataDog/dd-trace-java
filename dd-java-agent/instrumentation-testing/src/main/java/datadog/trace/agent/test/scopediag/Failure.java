package datadog.trace.agent.test.scopediag;

/**
 * A derived failure classification. Shared by {@link ContinuationRecord} (continuation-lifetime
 * failures) and {@link ScopeRecord} (scope-lifetime failures). See {@link
 * ScopeDiagnosticsReport#hasProblems()} for which of these fail a test versus are report-only.
 */
public enum Failure {
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

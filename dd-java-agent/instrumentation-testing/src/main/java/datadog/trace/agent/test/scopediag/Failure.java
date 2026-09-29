package datadog.trace.agent.test.scopediag;

/** A derived scope or continuation lifecycle finding. */
public enum Failure {
  /** Continuation captured but never resolved within the window. */
  LEAKED,
  /** Continuation resolved/resumed after the root span of its trace was already written. */
  LATE_FINISH,
  /** Continuation resolved more than once. */
  DOUBLE_FINISH,
  /** Activation rejected after resolution; the returned scope is a no-op (advisory). */
  ACTIVATE_AFTER_RESOLVE,
  /** Scope close attempted from a thread other than its owner. */
  CLOSE_WRONG_THREAD,
  /** Owner thread attempted to close a scope below the stack top. */
  CLOSE_OUT_OF_ORDER,
  /** Scope opened but never closed within the window. */
  NEVER_CLOSED
}

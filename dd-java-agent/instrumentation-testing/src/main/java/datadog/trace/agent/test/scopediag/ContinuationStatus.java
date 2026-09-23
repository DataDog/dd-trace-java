package datadog.trace.agent.test.scopediag;

/**
 * Derived continuation state used when rendering a timeline. Resolved states describe the first
 * recorded terminal observation, not which racing operation completed the core transition.
 */
public enum ContinuationStatus {
  /** Resolution observed through scope cleanup. */
  FINISHED,
  /** Resolution observed through an explicit release, including a clean release after a hold. */
  RELEASED,
  /** Captured (and possibly resumed) but never resolved within the recording window. */
  LEAKED
}

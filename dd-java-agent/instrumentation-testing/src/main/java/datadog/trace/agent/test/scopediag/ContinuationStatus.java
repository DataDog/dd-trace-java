package datadog.trace.agent.test.scopediag;

/**
 * Derived continuation state used when rendering a timeline. Resolution can be observed through a
 * failed resume; its cause is supplied by the first recorded terminal event when available.
 */
public enum ContinuationStatus {
  /** Resolution observed through scope cleanup. */
  FINISHED,
  /** Resolution observed through an explicit release, including a clean release after a hold. */
  RELEASED,
  /** Cancellation observed through a failed resume without a recorded terminal event. */
  RESOLVED,
  /** Capture and final resolution are outside this window's ownership. */
  EXTERNAL,
  /** Captured (and possibly resumed) but never resolved within the recording window. */
  LEAKED
}

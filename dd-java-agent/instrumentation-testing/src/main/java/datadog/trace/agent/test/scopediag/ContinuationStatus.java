package datadog.trace.agent.test.scopediag;

/** Derived continuation state used when rendering a timeline. */
public enum ContinuationStatus {
  /** Resolved normally (all activations closed or a clean cancel with no outstanding work). */
  FINISHED,
  /** Resolved via the cancel-with-outstanding-work path. */
  CANCELLED,
  /** Captured (and possibly resumed) but never resolved within the recording window. */
  LEAKED
}

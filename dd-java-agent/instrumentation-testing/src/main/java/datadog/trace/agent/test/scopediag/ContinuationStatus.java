package datadog.trace.agent.test.scopediag;

/**
 * Derived lifecycle state of a continuation, for rendering. The authoritative bug signal is the
 * {@link Failure} set, not this status.
 */
public enum ContinuationStatus {
  /** Captured but not yet resumed or resolved. */
  CAPTURED,
  /** Resumed at least once but not yet resolved. */
  RESUMED,
  /** Resolved normally (all activations closed or a clean cancel with no outstanding work). */
  FINISHED,
  /** Resolved via the cancel-with-outstanding-work path. */
  CANCELLED,
  /** Captured (and possibly resumed) but never resolved within the recording window. */
  LEAKED
}

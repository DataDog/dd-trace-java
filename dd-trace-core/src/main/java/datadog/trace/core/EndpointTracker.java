package datadog.trace.core;

/** A simple listener-like interface for tracking the end-point writes */
public interface EndpointTracker {
  /**
   * Called when a local root span (associated with an end-point) is written
   *
   * @param span the local root span
   * @param traceSampled {@literal true} if the containing trace is to be kept (sampled)
   * @param checkpointsSampled {@literal true} if the checkpoints for the local root span and its
   *     subtree were kept (sampled)
   */
  void endpointWritten(DDSpan span, boolean traceSampled, boolean checkpointsSampled);
}

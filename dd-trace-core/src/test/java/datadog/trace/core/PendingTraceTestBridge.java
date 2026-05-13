package datadog.trace.core;

/**
 * Bridge class to allow tests to access package-private method exposed by the {@code PendingTrace}
 */
public class PendingTraceTestBridge {
  public static int getPendingReferenceCount(PendingTrace pendingTrace) {
    return pendingTrace.getPendingReferenceCount();
  }
}

package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilerContext {

  long getSpanId();

  /**
   * @return the span id of the local root span, or the span itself
   */
  long getRootSpanId();

  /**
   * @return upper 64 bits of the 128-bit trace id, or 0 if not available
   */
  default long getTraceIdHigh() {
    return 0L;
  }

  /**
   * @return lower 64 bits of the 128-bit trace id, or 0 if not available
   */
  default long getTraceIdLow() {
    return 0L;
  }

  int getEncodedOperationName();

  CharSequence getOperationName();

  int getEncodedResourceName();

  CharSequence getResourceName();
}

package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilerContext {

  long getSpanId();

  /**
   * @return the span id of the local root span, or the span itself
   */
  long getRootSpanId();

  /**
   * @return the span id of the parent span, or 0 if this is the root
   */
  long getParentSpanId();

  int getEncodedOperationName();

  CharSequence getOperationName();

  int getEncodedResourceName();

  CharSequence getResourceName();
}

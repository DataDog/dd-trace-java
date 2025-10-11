package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilerContext {

  long getSpanId();

  /**
   * @return the span id of the local root span, or the span itself
   */
  long getRootSpanId();

  int getEncodedOperationName();

  CharSequence getOperationName();

  int getEncodedResourceName();

  CharSequence getResourceName();
}

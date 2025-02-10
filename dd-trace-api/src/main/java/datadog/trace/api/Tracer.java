package datadog.trace.api;

import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.context.TraceScope;

/** A class with Datadog tracer features. */
public interface Tracer {

  /** Get the trace id of the active trace. Returns 0 if there is no active trace. */
  String getTraceId();

  /**
   * Get the span id of the active span of the active trace. Returns 0 if there is no active trace.
   */
  String getSpanId();

  /**
   * Checks whether asynchronous propagation is enabled, meaning this context will propagate across
   * asynchronous boundaries.
   *
   * @deprecated Unstable API. Might be removed at any time.
   * @return {@code true} if asynchronous propagation is enabled, {@code false} otherwise.
   */
  @Deprecated
  boolean isAsyncPropagationEnabled();

  /**
   * Enables or disables asynchronous propagation for the active span.
   *
   * <p>Asynchronous propagation is enabled by default from {@link
   * ConfigDefaults#DEFAULT_ASYNC_PROPAGATING}.
   *
   * @deprecated Unstable API. Might be removed at any time.
   * @param asyncPropagationEnabled {@code true} to enable asynchronous propagation, {@code false}
   *     to disable it.
   */
  @Deprecated
  void setAsyncPropagationEnabled(boolean asyncPropagationEnabled);

  /**
   * Add a new interceptor to the tracer. Interceptors with duplicate priority to existing ones are
   * ignored.
   *
   * @param traceInterceptor
   * @return false if an interceptor with same priority exists.
   */
  boolean addTraceInterceptor(TraceInterceptor traceInterceptor);

  TraceScope muteTracing();
}

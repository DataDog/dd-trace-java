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
   * @return {@code true} if asynchronous propagation is enabled, {@code false} otherwise.
   */
  boolean isAsyncPropagationEnabled();

  /**
   * Enables or disables asynchronous propagation for the active span.
   *
   * <p>Asynchronous propagation is enabled by default from {@link
   * ConfigDefaults#DEFAULT_ASYNC_PROPAGATING}.
   *
   * @param asyncPropagationEnabled @{@code true} to enable asynchronous propagation, {@code false}
   *     to disable it.
   */
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

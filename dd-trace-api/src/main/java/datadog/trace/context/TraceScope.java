package datadog.trace.context;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import java.io.Closeable;

/** An object which can propagate a datadog trace across multiple threads. */
public interface TraceScope extends Closeable {

  /** Close the activated context and allow any underlying spans to finish. */
  @Override
  void close();

  /**
   * Used to pass async context between workers. A trace will not be reported until all spans and
   * continuations are resolved. You must call activate (and close on the returned scope) or cancel
   * on each continuation to avoid discarding traces.
   */
  interface Continuation {

    /**
     * Prevent the trace attached to this scope from reporting until the continuation is explicitly
     * cancelled. You must call {@link #cancel()} at some point to avoid discarding traces.
     *
     * <p>Use this when you want to let multiple threads activate the continuation concurrently and
     * close their scopes without fear of prematurely closing the related span.
     */
    Continuation hold();

    /**
     * Activate the continuation.
     *
     * <p>Should be called on the child thread.
     *
     * <p>Consider calling this in a try-with-resources initialization block to ensure the returned
     * scope is closed properly.
     */
    TraceScope activate();

    /** Allow trace to stop waiting on this continuation for reporting. */
    void cancel();
  }

  /**
   * @deprecated Replaced by {@link Tracer#captureActiveSpan()}.
   *     <p>When asynchronous propagation is enabled, prevent the <strong>currently active
   *     trace</strong>, which may differ from this scope instance, from reporting until the
   *     returned Continuation is either activated (and the returned scope is closed) or the
   *     continuation is canceled. Should be called on the parent thread.
   * @return Continuation of the active span, no-op continuation if there's no active span or
   *     asynchronous propagation is disabled.
   */
  @Deprecated
  default Continuation capture() {
    return GlobalTracer.get().captureActiveSpan();
  }

  /**
   * @deprecated Replaced by {@code capture().hold()}.
   */
  @Deprecated
  default Continuation captureConcurrent() {
    return capture().hold();
  }

  /**
   * @deprecated Replaced by {@link Tracer#isAsyncPropagationEnabled()}.
   *     <p>Calling this method will check whether asynchronous propagation is enabled <strong>for
   *     the active scope</strong>, not this scope instance.
   * @return {@code true} if asynchronous propagation is enabled <strong>for the active
   *     scope</strong>, {@code false} otherwise.
   */
  @Deprecated
  default boolean isAsyncPropagating() {
    return GlobalTracer.get().isAsyncPropagationEnabled();
  }

  /**
   * @deprecated Replaced by {@link Tracer#setAsyncPropagationEnabled(boolean)}}.
   *     <p>Calling this method will enable or disable asynchronous propagation <strong>for the
   *     active scope</strong>, not this scope instance.
   * @param value {@code true} to enable asynchronous propagation, {@code false} to disable it.
   */
  @Deprecated
  default void setAsyncPropagation(boolean value) {
    GlobalTracer.get().setAsyncPropagationEnabled(value);
  }
}

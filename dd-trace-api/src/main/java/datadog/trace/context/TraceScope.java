package datadog.trace.context;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import java.io.Closeable;

/** An object which can propagate a datadog trace across multiple threads. */
public interface TraceScope extends Closeable {
  /**
   * Prevent the trace attached to this TraceScope from reporting until the returned Continuation is
   * either activated (and the returned scope is closed), or canceled.
   *
   * <p>Should be called on the parent thread.
   */
  Continuation capture();

  /**
   * Prevent the trace attached to this TraceScope from reporting until the returned Continuation is
   * either activated (and the returned scope is closed), or canceled.
   *
   * <p>Should be called on the parent thread.
   *
   * <p>If the returned {@link Continuation} is activated, it needs to be canceled in addition to
   * the returned {@link TraceScope} being closed. This is to allow multiple concurrent threads that
   * activate the continuation to race in a safe way, and close the scopes without fear of closing
   * the related {@code Span} prematurely.
   */
  Continuation captureConcurrent();

  /** Close the activated context and allow any underlying spans to finish. */
  @Override
  void close();

  /**
   * @deprecated Replaced by {@link Tracer#isAsyncPropagationEnabled()}.
   *     <p>Calling this method will check whether asynchronous propagation is active <strong>for
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
   * @param value @{@code true} to enable asynchronous propagation, {@code false} to disable it.
   */
  @Deprecated
  default void setAsyncPropagation(boolean value) {
    GlobalTracer.get().setAsyncPropagationEnabled(value);
  }

  /**
   * Used to pass async context between workers. A trace will not be reported until all spans and
   * continuations are resolved. You must call activate (and close on the returned scope) or cancel
   * on each continuation to avoid discarding traces.
   */
  interface Continuation {

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
}

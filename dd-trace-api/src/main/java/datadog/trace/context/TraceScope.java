package datadog.trace.context;

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

  /** If true, this context will propagate across async boundaries. */
  boolean isAsyncPropagating();

  /**
   * Enable or disable async propagation. Async propagation is initially set to false.
   *
   * @param value The new propagation value. True == propagate. False == don't propagate.
   */
  void setAsyncPropagation(boolean value);

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

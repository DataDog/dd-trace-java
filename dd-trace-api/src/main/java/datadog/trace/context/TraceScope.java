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
   * Used to pass async context between workers. Either activate or cancel must be called on each
   * continuation instance to allow the trace to be reported.
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

    /**
     * Cancel the continuation. This also closes parent scope.
     *
     * @deprecated use {@link #cancel()} instead. Will be removed in the next release.
     */
    @Deprecated
    void close();

    /**
     * Close the continuation.
     *
     * @deprecated use {@link #cancel()} instead. Will be removed in the next release.
     * @param closeContinuationScope true iff parent scope should also be closed
     */
    @Deprecated
    void close(boolean closeContinuationScope);
  }
}

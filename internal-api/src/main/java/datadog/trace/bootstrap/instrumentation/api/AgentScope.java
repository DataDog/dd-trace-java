package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Tracer;
import java.io.Closeable;

public interface AgentScope extends ContextScope, Tracer.Blackhole, Closeable {
  AgentSpan span();

  @Override
  default Context context() {
    return span();
  }

  @Override
  void close();

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
    AgentScope activate();

    /** Allow trace to stop waiting on this continuation for reporting. */
    void cancel();

    /** Provide access to the captured span */
    AgentSpan span();

    /** Provide access to the captured context */
    default Context context() {
      return span();
    }
  }
}

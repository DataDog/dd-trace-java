package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;
import java.io.Closeable;

public interface AgentScope extends TraceScope, Closeable {
  AgentSpan span();

  @Override
  void setAsyncPropagation(boolean value);

  boolean checkpointed();

  @Override
  void close();

  interface Continuation extends TraceScope.Continuation {
    /**
     * Mark that the continuation will migrate to another thread. Calling this method will lead to
     * emitting events to allow the profiler to know that the span has forked from the current
     * thread and will resume elsewhere. It does not need to be called except when thread migration
     * is expected to happen, and should not be called for e.g. future style APIs where a
     * continuation will be created, awaited, and closed all on the same thread.
     */
    void migrate();

    AgentSpan getSpan();
  }
}

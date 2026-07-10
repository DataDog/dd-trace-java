package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.context.TraceScope;
import java.io.Closeable;

public interface AgentScope extends ContextScope, TraceScope, Closeable {
  AgentSpan span();

  @Override
  default Context context() {
    return span();
  }

  @Override
  void close();

  @Deprecated
  interface Continuation extends TraceScope.Continuation, ContextContinuation {
    @Override
    Continuation hold();

    @Override
    AgentScope activate();

    /** Provide access to the captured span */
    AgentSpan span();
  }
}

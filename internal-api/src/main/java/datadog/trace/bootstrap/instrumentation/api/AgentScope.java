package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Tracer;
import datadog.trace.context.TraceScope;
import java.io.Closeable;

public interface AgentScope extends ContextScope, TraceScope, Tracer.Blackhole, Closeable {
  AgentSpan span();

  @Override
  default Context context() {
    return span();
  }

  @Override
  void close();

  interface Continuation extends TraceScope.Continuation {
    @Override
    Continuation hold();

    @Override
    AgentScope activate();

    /** Provide access to the captured span */
    AgentSpan span();

    /** Provide access to the captured context */
    default Context context() {
      return span();
    }
  }
}

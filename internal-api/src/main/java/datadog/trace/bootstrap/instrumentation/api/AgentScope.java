package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;
import java.io.Closeable;

public interface AgentScope extends TraceScope, Closeable {
  AgentSpan span();

  byte source();

  @Override
  Continuation capture();

  @Override
  void close();

  interface Continuation extends TraceScope.Continuation {

    @Override
    Continuation hold();

    @Override
    AgentScope activate();

    /** Provide access to the captured span */
    AgentSpan getSpan();
  }
}

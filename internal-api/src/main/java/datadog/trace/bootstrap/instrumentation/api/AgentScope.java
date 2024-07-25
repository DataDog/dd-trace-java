package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.context.TraceScope;
import java.io.Closeable;
import javax.annotation.Nullable;

public interface AgentScope extends TraceScope, Closeable {
  AgentSpan span();

  byte source();

  @Nullable
  @Override
  Continuation capture();

  @Override
  Continuation captureConcurrent();

  @Override
  void setAsyncPropagation(boolean value);

  @Override
  void close();

  interface Continuation extends TraceScope.Continuation {

    @Override
    AgentScope activate();

    /** Provide access to the captured span */
    AgentSpan getSpan();
  }
}

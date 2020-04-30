package datadog.trace.bootstrap.instrumentation.api;

import java.io.Closeable;

public interface AgentScope extends Closeable {
  AgentSpan span();

  void setAsyncPropagation(boolean value);

  @Override
  void close();
}

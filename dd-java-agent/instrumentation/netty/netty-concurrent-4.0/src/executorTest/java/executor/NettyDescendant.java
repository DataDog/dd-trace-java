package executor;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class NettyDescendant implements Runnable {
  private final String parent;

  public NettyDescendant(String parent) {
    this.parent = parent;
  }

  @Override
  public void run() {
    AgentSpan span = startSpan("test", parent + "-child");
    try (ContextScope scope = activateSpan(span)) {
    } finally {
      span.finish();
    }
  }
}

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;

public final class Descendant implements Runnable {

  private final String parent;

  public Descendant(String parent) {
    this.parent = parent;
  }

  @Override
  public void run() {
    AgentSpan span = startSpan(parent + "-child");
    try (TraceScope scope = activateSpan(span)) {

    } finally {
      span.finish();
    }
  }
}

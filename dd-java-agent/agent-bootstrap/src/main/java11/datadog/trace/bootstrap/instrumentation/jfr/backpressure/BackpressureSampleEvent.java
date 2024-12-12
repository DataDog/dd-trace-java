package datadog.trace.bootstrap.instrumentation.jfr.backpressure;

import datadog.trace.bootstrap.instrumentation.jfr.ContextualEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("datadog.BackpressureSample")
@Label("Backpressure Sample")
@Description("Datadog backpressure sample event.")
@Category("Datadog")
public class BackpressureSampleEvent extends Event implements ContextualEvent {
  @Label("Policy")
  private final Class<?> policy;

  @Label("Task")
  private final Class<?> task;

  @Label("Local Root Span Id")
  private long localRootSpanId;

  @Label("Span Id")
  private long spanId;

  public BackpressureSampleEvent(Class<?> policy, Class<?> task) {
    this.policy = policy;
    this.task = task;
    captureContext();
  }

  @Override
  public void setContext(long localRootSpanId, long spanId) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
  }
}

package datadog.trace.bootstrap.instrumentation.azure;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shares an Azure Durable orchestration invocation and its span across worker classloaders. */
public final class DurableOrchestrationState {
  private static final ContextKey<DurableOrchestrationState> KEY =
      ContextKey.named("azure-durable-orchestration");

  private final AgentSpan parentSpan;
  private final String functionName;
  private final AtomicReference<AgentSpan> span = new AtomicReference<>();
  private final AtomicBoolean errorRecorded = new AtomicBoolean();

  private DurableOrchestrationState(AgentSpan parentSpan, String functionName) {
    this.parentSpan = parentSpan;
    this.functionName = functionName;
  }

  public static ContextScope activate(AgentSpan remoteSpan, String functionName) {
    Context context = Context.current();
    final AgentSpan parentSpan = remoteSpan != null && remoteSpan.isValid() ? remoteSpan : null;
    if (parentSpan != null) {
      context = context.with(parentSpan);
    }
    return context.with(KEY, new DurableOrchestrationState(parentSpan, functionName)).attach();
  }

  public static DurableOrchestrationState current() {
    return Context.current().get(KEY);
  }

  public AgentSpan span() {
    return span.get();
  }

  public AgentSpan parentSpan() {
    return parentSpan;
  }

  public String functionName() {
    return functionName;
  }

  public boolean errorRecorded() {
    return errorRecorded.get();
  }

  public void markErrorRecorded() {
    errorRecorded.set(true);
  }

  public AgentSpan setSpan(AgentSpan span) {
    return this.span.getAndSet(span);
  }
}

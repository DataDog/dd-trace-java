package datadog.trace.instrumentation.azure.functions.worker;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Carries the Azure orchestration invocation and its span through the synchronous worker call. */
public final class DurableOrchestrationState {
  private static final ContextKey<DurableOrchestrationState> KEY =
      ContextKey.named("azure-durable-orchestration");

  private AgentSpan span;

  private DurableOrchestrationState() {}

  public static ContextScope activate(AgentSpan remoteSpan) {
    Context context = Context.current();
    if (remoteSpan != null && remoteSpan.isValid()) {
      context = context.with(remoteSpan);
    }
    return context.with(KEY, new DurableOrchestrationState()).attach();
  }

  public static DurableOrchestrationState current() {
    return Context.current().get(KEY);
  }

  public AgentSpan span() {
    return span;
  }

  public AgentSpan setSpan(AgentSpan span) {
    final AgentSpan previous = this.span;
    this.span = span;
    return previous;
  }
}

package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;

/** Shares an Azure Durable orchestration invocation and its span across worker classloaders. */
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

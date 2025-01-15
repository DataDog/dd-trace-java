package datadog.trace.bootstrap.instrumentation.rmi;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public class ThreadLocalContext {
  public static final ThreadLocalContext THREAD_LOCAL_CONTEXT = new ThreadLocalContext();
  private final ThreadLocal<AgentSpanContext> local;

  public ThreadLocalContext() {
    local = new ThreadLocal<>();
  }

  public void set(final AgentSpanContext context) {
    local.set(context);
  }

  public AgentSpanContext getAndResetContext() {
    final AgentSpanContext context = local.get();
    if (context != null) {
      local.remove();
    }
    return context;
  }
}

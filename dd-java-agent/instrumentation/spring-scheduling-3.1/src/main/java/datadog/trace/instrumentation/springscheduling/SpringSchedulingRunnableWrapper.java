package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.DECORATE;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.SCHEDULED_CALL;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SpringSchedulingRunnableWrapper implements Runnable {
  private static final boolean LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "spring-scheduling");
  private final Runnable runnable;

  private SpringSchedulingRunnableWrapper(final Runnable runnable) {
    this.runnable = runnable;
  }

  @Override
  public void run() {
    final AgentSpan span =
        LEGACY_TRACING ? startSpan(SCHEDULED_CALL) : startSpan(SCHEDULED_CALL, null);
    DECORATE.afterStart(span);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.onRun(span, runnable);
      scope.setAsyncPropagation(true);

      try {
        runnable.run();
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        throw throwable;
      }
    } finally {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task) {
    if (task instanceof SpringSchedulingRunnableWrapper || exclude(RUNNABLE, task)) {
      return task;
    }
    return new SpringSchedulingRunnableWrapper(task);
  }

  @Override
  public String toString() {
    return runnable.toString();
  }
}

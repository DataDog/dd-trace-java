package datadog.trace.instrumentation.springscheduling;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.DECORATE;
import static datadog.trace.instrumentation.springscheduling.SpringSchedulingDecorator.SCHEDULED_CALL;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class SpringSchedulingRunnableWrapper implements Runnable {
  private final Runnable runnable;

  private SpringSchedulingRunnableWrapper(final Runnable runnable) {
    this.runnable = runnable;
  }

  @Override
  public void run() {
    final AgentSpan span = startSpan(SCHEDULED_CALL);
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
    // We wrap only lambdas' anonymous classes and if given object has not already been wrapped.
    // Anonymous classes have '/' in class name which is not allowed in 'normal' classes.
    if (task instanceof SpringSchedulingRunnableWrapper) {
      return task;
    }
    return new SpringSchedulingRunnableWrapper(task);
  }
}

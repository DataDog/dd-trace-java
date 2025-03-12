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
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import org.springframework.scheduling.SchedulingAwareRunnable;

public class SpringSchedulingRunnableWrapper implements Runnable {
  private static final boolean LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "spring-scheduling");

  static class SchedulingAware extends SpringSchedulingRunnableWrapper
      implements SchedulingAwareRunnable {

    private static final MethodHandle GET_QUALIFIER_MH =
        new MethodHandles(SchedulingAwareRunnable.class.getClassLoader())
            .method(SchedulingAwareRunnable.class, "getQualifier");

    SchedulingAware(Runnable runnable) {
      super(runnable);
    }

    @Override
    public boolean isLongLived() {
      return ((SchedulingAwareRunnable) runnable).isLongLived();
    }

    // this is implemented on 6.1+
    public String getQualifier() {
      if (GET_QUALIFIER_MH != null) {
        try {
          return (String) GET_QUALIFIER_MH.invoke(runnable);
        } catch (Throwable ignored) {
        }
      }
      return null;
    }
  }

  protected final Runnable runnable;

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
    if (task instanceof SchedulingAwareRunnable) {
      return new SchedulingAware(task);
    }
    return new SpringSchedulingRunnableWrapper(task);
  }

  @Override
  public String toString() {
    return runnable.toString();
  }
}

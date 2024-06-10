package datadog.trace.bootstrap.otel.instrumentation;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;

public final class InstrumentedTaskClasses {

  public static boolean canInstrumentTaskClass(Class<?> clazz) {
    return !ExcludeFilter.exclude(RUNNABLE, clazz);
  }

  private InstrumentedTaskClasses() {}
}

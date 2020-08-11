package datadog.trace.instrumentation.subtrace;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.subTraceContext;

import datadog.trace.bootstrap.instrumentation.api.SubTrace;
import net.bytebuddy.asm.Advice;

public class SubTraceAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void start(
      @Advice.Local("context") SubTrace.Context context,
      @Advice.Local("start") long start,
      @Advice.Local("startRunningDuration") long startRunningDuration) {
    context = subTraceContext();
    if (context != null) {
      start = System.nanoTime();
      startRunningDuration = context.getRunningDuration();
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stop(
      @Advice.Origin final Class clazz,
      @Advice.Origin("#m") final String method,
      @Advice.Local("context") final SubTrace.Context context,
      @Advice.Local("start") final long start,
      @Advice.Local("startRunningDuration") final long startRunningDuration,
      @Advice.Thrown final Throwable throwable) {
    if (context != null) {
      context.collect(clazz, method, start, startRunningDuration, throwable);
    }
  }
}

package datadog.smoketest.loginjection;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Trace;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.Tracer;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public abstract class BaseApplication {
  public static final long TIMEOUT_IN_NANOS = TimeUnit.SECONDS.toNanos(10);

  public abstract void doLog(String message);

  public void run() throws InterruptedException {
    doLog("BEFORE FIRST SPAN");

    firstTracedMethod();

    doLog("AFTER FIRST SPAN");

    secondTracedMethod();

    if (!waitForCondition(() -> !getLogInjectionEnabled())) {
      throw new RuntimeException("Logs injection config was never updated");
    }

    thirdTracedMethod();

    if (!waitForCondition(() -> getLogInjectionEnabled())) {
      throw new RuntimeException("Logs injection config was never updated a second time");
    }

    forthTracedMethod();

    // Logs for "AFTER SECOND SPAN" and "AFTER THIRD SPAN" don't exist to avoid the race between
    // traces and logs. The tester waits for traces before changing config
    doLog("AFTER FORTH SPAN");

    // Sleep to allow the trace to be reported
    Thread.sleep(400);
  }

  private static boolean getLogInjectionEnabled() {
    try {
      Tracer tracer = GlobalTracer.get();
      Method captureTraceConfig = tracer.getClass().getMethod("captureTraceConfig");
      return ((TraceConfig) captureTraceConfig.invoke(tracer)).isLogsInjectionEnabled();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @Trace
  public void firstTracedMethod() {
    doLog("INSIDE FIRST SPAN");
    System.out.println(
        "FIRSTTRACEID "
            + CorrelationIdentifier.getTraceId()
            + " "
            + CorrelationIdentifier.getSpanId());
  }

  @Trace
  public void secondTracedMethod() {
    doLog("INSIDE SECOND SPAN");
    System.out.println(
        "SECONDTRACEID "
            + CorrelationIdentifier.getTraceId()
            + " "
            + CorrelationIdentifier.getSpanId());
  }

  @Trace
  public void thirdTracedMethod() {
    doLog("INSIDE THIRD SPAN");
    System.out.println(
        "THIRDTRACEID "
            + CorrelationIdentifier.getTraceId()
            + " "
            + CorrelationIdentifier.getSpanId());
  }

  @Trace
  public void forthTracedMethod() {
    doLog("INSIDE FORTH SPAN");
    System.out.println(
        "FORTHTRACEID "
            + CorrelationIdentifier.getTraceId()
            + " "
            + CorrelationIdentifier.getSpanId());
  }

  private static boolean waitForCondition(Supplier<Boolean> condition) throws InterruptedException {
    long startTime = System.nanoTime();
    while (System.nanoTime() - startTime < TIMEOUT_IN_NANOS) {
      if (condition.get()) {
        return true;
      }

      Thread.sleep(100);
    }

    return false;
  }
}

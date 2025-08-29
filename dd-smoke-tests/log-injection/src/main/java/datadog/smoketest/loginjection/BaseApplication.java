package datadog.smoketest.loginjection;

import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;

import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Trace;
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

    if (!waitForCondition(() -> Boolean.FALSE.equals(getLogInjectionEnabled()))) {
      throw new RuntimeException("Logs injection config was never updated");
    }

    thirdTracedMethod();

    if (!waitForCondition(() -> Boolean.TRUE.equals(getLogInjectionEnabled()))) {
      throw new RuntimeException("Logs injection config was never updated a second time");
    }

    forthTracedMethod();

    // Logs for "AFTER SECOND SPAN" and "AFTER THIRD SPAN" don't exist to avoid the race between
    // traces and logs. The tester waits for traces before changing config
    doLog("AFTER FORTH SPAN");

    // Sleep to allow the trace to be reported
    Thread.sleep(400);
  }

  private static Object getLogInjectionEnabled() {
    ConfigSetting configSetting =
        ConfigCollector.getAppliedConfigSetting(
            LOGS_INJECTION_ENABLED, ConfigCollector.get().collect());
    if (configSetting == null) {
      return null;
    }
    return configSetting.value;
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

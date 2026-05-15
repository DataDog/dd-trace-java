package datadog.smoketest.loginjection;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Trace;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.Tracer;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.LogManager;

public abstract class BaseApplication {
  public static final long TIMEOUT_IN_NANOS = TimeUnit.SECONDS.toNanos(30);

  private static final AtomicInteger DIAG_SEQ = new AtomicInteger(0);

  public abstract void doLog(String message);

  public void run() throws InterruptedException {
    dumpDiagnostics("start-of-run");

    doLog("BEFORE FIRST SPAN");
    dumpDiagnostics("after-BEFORE-FIRST-SPAN");

    firstTracedMethod();
    dumpDiagnostics("after-firstTracedMethod");

    doLog("AFTER FIRST SPAN");
    dumpDiagnostics("after-AFTER-FIRST-SPAN");

    secondTracedMethod();
    dumpDiagnostics("after-secondTracedMethod");

    if (!waitForCondition(() -> !getLogInjectionEnabled())) {
      throw new RuntimeException("Logs injection config was never updated");
    }

    thirdTracedMethod();
    dumpDiagnostics("after-thirdTracedMethod");

    if (!waitForCondition(() -> getLogInjectionEnabled())) {
      throw new RuntimeException("Logs injection config was never updated a second time");
    }

    forthTracedMethod();
    dumpDiagnostics("after-forthTracedMethod");

    // Logs for "AFTER SECOND SPAN" and "AFTER THIRD SPAN" don't exist to avoid the race between
    // traces and logs. The tester waits for traces before changing config
    doLog("AFTER FORTH SPAN");
    dumpDiagnostics("after-AFTER-FORTH-SPAN");

    // Sleep to allow the trace to be reported
    Thread.sleep(400);
    dumpDiagnostics("end-of-run");
  }

  private static void dumpDiagnostics(String label) {
    int seq = DIAG_SEQ.incrementAndGet();
    StringBuilder sb = new StringBuilder();
    sb.append("DDIAG seq=").append(seq).append(" label=").append(label);
    try {
      LogManager lm = LogManager.getLogManager();
      sb.append(" lm_class=").append(lm == null ? "null" : lm.getClass().getName());
    } catch (Throwable t) {
      sb.append(" lm_class=ERR:").append(t.getClass().getName());
    }
    sb.append(" jul_mgr_prop=").append(System.getProperty("java.util.logging.manager", "UNSET"));
    sb.append(" jvm_vendor=").append(System.getProperty("java.vendor", "?"));
    sb.append(" jvm_version=").append(System.getProperty("java.version", "?"));
    String logfile = System.getProperty("dd.test.logfile");
    sb.append(" logfile=").append(logfile);
    if (logfile != null) {
      File f = new File(logfile);
      if (f.exists()) {
        sb.append(" file_size=").append(f.length());
        try {
          long lineCount = Files.lines(f.toPath()).count();
          sb.append(" file_lines=").append(lineCount);
        } catch (Throwable t) {
          sb.append(" file_lines=ERR:").append(t.getClass().getName());
        }
      } else {
        sb.append(" file_exists=false");
      }
    }
    System.out.println(sb.toString());
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

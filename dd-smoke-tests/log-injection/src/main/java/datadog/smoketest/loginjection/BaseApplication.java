package datadog.smoketest.loginjection;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Trace;

public abstract class BaseApplication {
  public abstract void doLog(String message);

  public void run() throws InterruptedException {
    doLog("BEFORE FIRST SPAN");

    firstTracedMethod();

    doLog("AFTER FIRST SPAN");

    secondTracedMethod();

    // Sleep to allow the trace to be reported
    Thread.sleep(1000);
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
}

package datadog.trace.instrumentation.weaver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import weaver.framework.SuiteEvent;
import weaver.framework.SuiteFinished;
import weaver.framework.SuiteStarted;
import weaver.framework.TestFinished;

public class DatadogWeaverReporter {

  public static void handle(SuiteEvent event) {
    Instant now = Instant.now();
    String timestamp =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(now);
    if (event instanceof SuiteStarted) {
      System.out.println("[instr_debug " + timestamp + "] suite started");
    } else if (event instanceof SuiteFinished) {
      System.out.println("[instr_debug " + timestamp + "] suite finished");
    } else if (event instanceof TestFinished) {
      System.out.println("[instr_debug " + timestamp + "] test finished");
    }
  }
}

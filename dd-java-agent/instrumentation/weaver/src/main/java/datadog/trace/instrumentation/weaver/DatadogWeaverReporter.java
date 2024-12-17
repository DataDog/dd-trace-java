package datadog.trace.instrumentation.weaver;

import weaver.framework.SuiteEvent;
import weaver.framework.SuiteFinished;
import weaver.framework.SuiteStarted;
import weaver.framework.TestFinished;

public class DatadogWeaverReporter {

  public static void handle(SuiteEvent event) {
    if (event instanceof SuiteStarted) {
      System.out.println("!!! Suite started");
    } else if (event instanceof SuiteFinished) {
      System.out.println("!!! Suite Finished");
    } else if (event instanceof TestFinished) {
      System.out.println("!!! Test finished");
    }
  }
}

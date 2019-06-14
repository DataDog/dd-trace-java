package datadog.smoketest.profiling;

import datadog.trace.api.Trace;

public class ProfilingTestApplication {

  public static void main(final String[] args) throws InterruptedException {
    while (true) {
      tracedMethod();
    }
  }

  @Trace
  private static void tracedMethod() throws InterruptedException {
    System.out.println("Tracing");
    Thread.sleep(100);
  }
}

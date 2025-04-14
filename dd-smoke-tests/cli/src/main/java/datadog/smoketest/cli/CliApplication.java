package datadog.smoketest.cli;

import datadog.trace.api.Trace;
import de.thetaphi.forbiddenapis.SuppressForbidden;

/** Simple application that sleeps then quits. */
public class CliApplication {

  @SuppressForbidden
  public static void main(final String[] args) throws InterruptedException {
    final CliApplication app = new CliApplication();

    // Sleep to ensure all of the processes are running
    Thread.sleep(5000);

    System.out.println("Calling example trace");

    app.exampleTrace();

    System.out.println("Finished calling example trace");

    // Sleep to allow the trace to be reported
    Thread.sleep(1000);
  }

  @Trace(operationName = "example")
  public void exampleTrace() throws InterruptedException {
    Thread.sleep(500);
  }
}

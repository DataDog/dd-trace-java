import datadog.trace.api.Trace;

public class PeriodicTask implements Runnable {
  private int runCount;

  public int getRunCount() {
    return runCount;
  }

  @Override
  public void run() {
    periodicRun();
  }

  @Trace(operationName = "periodicRun")
  private void periodicRun() {
    runCount++;
  }
}

package forkjoin;

import datadog.trace.api.Trace;

public class PeriodicTask implements Runnable {
  private volatile int runCount;
  private boolean finished = false;

  public int getRunCount() {
    return runCount;
  }

  public void ensureFinished() {
    synchronized (this) {
      this.finished = true;
    }
  }

  @Override
  public void run() {
    synchronized (this) {
      if (!finished) {
        periodicRun();
      }
    }
  }

  @Trace(operationName = "periodicRun")
  private void periodicRun() {
    runCount++;
  }
}

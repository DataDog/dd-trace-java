package datadog.trace.civisibility.test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public class ExecutionResults {

  private final LongAdder testsSkippedByItr = new LongAdder();
  private final AtomicBoolean hasFailedTestReplayTests = new AtomicBoolean();

  public void incrementTestsSkippedByItr() {
    testsSkippedByItr.increment();
  }

  public long getTestsSkippedByItr() {
    return testsSkippedByItr.sum();
  }

  public void setHasFailedTestReplayTests() {
    this.hasFailedTestReplayTests.set(true);
  }

  public boolean hasFailedTestReplayTests() {
    return hasFailedTestReplayTests.get();
  }
}

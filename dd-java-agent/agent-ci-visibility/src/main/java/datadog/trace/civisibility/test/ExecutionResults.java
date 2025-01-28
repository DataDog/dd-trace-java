package datadog.trace.civisibility.test;

import java.util.concurrent.atomic.LongAdder;

public class ExecutionResults {

  private final LongAdder testsSkippedByItr = new LongAdder();

  public void incrementTestsSkippedByItr() {
    testsSkippedByItr.increment();
  }

  public long getTestsSkippedByItr() {
    return testsSkippedByItr.sum();
  }
}

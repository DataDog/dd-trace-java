package datadog.trace.instrumentation.scalatest.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import org.scalatest.Canceled;
import org.scalatest.Outcome;
import org.scalatest.SuperEngine;
import scala.Function1;

public class TestExecutionWrapper implements scala.Function1<SuperEngine<?>.TestLeaf, Outcome> {
  private final scala.Function1<SuperEngine<?>.TestLeaf, Outcome> delegate;
  private final TestExecutionPolicy executionPolicy;

  private boolean executionFailed;
  private long duration;

  public TestExecutionWrapper(
      Function1<SuperEngine<?>.TestLeaf, Outcome> delegate, TestExecutionPolicy executionPolicy) {
    this.delegate = delegate;
    this.executionPolicy = executionPolicy;
  }

  @Override
  public Outcome apply(SuperEngine<?>.TestLeaf testLeaf) {
    executionFailed = false;

    long startTimestamp = System.currentTimeMillis();
    Outcome outcome = delegate.apply(testLeaf);
    duration = System.currentTimeMillis() - startTimestamp;

    if (outcome.isFailed()) {
      executionFailed = true;
      if (executionPolicy.suppressFailures()) {
        Throwable t = outcome.toOption().get();
        return Canceled.apply(
            new SuppressedTestFailedException("Test failed and will be retried", t, 0));
      }
    }

    return outcome;
  }

  public boolean retry() {
    return executionPolicy.retry(!executionFailed, duration);
  }
}

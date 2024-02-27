package datadog.trace.instrumentation.scalatest.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import org.scalatest.Canceled;
import org.scalatest.Outcome;
import org.scalatest.SuperEngine;
import scala.Function1;

public class TestExecutionWrapper implements scala.Function1<SuperEngine<?>.TestLeaf, Outcome> {
  private final scala.Function1<SuperEngine<?>.TestLeaf, Outcome> delegate;
  private final TestRetryPolicy retryPolicy;

  private boolean executionFailed;
  private long duration;

  public TestExecutionWrapper(
      Function1<SuperEngine<?>.TestLeaf, Outcome> delegate, TestRetryPolicy retryPolicy) {
    this.delegate = delegate;
    this.retryPolicy = retryPolicy;
  }

  @Override
  public Outcome apply(SuperEngine<?>.TestLeaf testLeaf) {
    executionFailed = false;

    long startTimestamp = System.currentTimeMillis();
    Outcome outcome = delegate.apply(testLeaf);
    duration = System.currentTimeMillis() - startTimestamp;

    if (outcome.isFailed()) {
      executionFailed = true;
      if (retryPolicy.retriesLeft() && retryPolicy.suppressFailures()) {
        Throwable t = outcome.toOption().get();
        return Canceled.apply(
            new SuppressedTestFailedException("Test failed and will be retried", t, 0));
      }
    }

    return outcome;
  }

  public boolean retry() {
    return retryPolicy.retriesLeft() && retryPolicy.retry(!executionFailed, duration);
  }
}

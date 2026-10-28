package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.TestListenerAdapter;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class TestParallelNativeQuarantinedExcluded {
  public static final AtomicInteger executions = new AtomicInteger();
  public static final AtomicInteger suppressed = new AtomicInteger();
  public static final AtomicInteger blocking = new AtomicInteger();
  private static CountDownLatch bothRetrying;
  private static CountDownLatch firstReported;
  private static final AtomicInteger retryCalls = new AtomicInteger();

  public static void reset() {
    executions.set(0);
    suppressed.set(0);
    blocking.set(0);
    retryCalls.set(0);
    bothRetrying = new CountDownLatch(2);
    firstReported = new CountDownLatch(1);
  }

  @DataProvider(name = "colliding", parallel = true)
  public Object[][] colliding() {
    return new Object[][] {{new Parameter()}, {new Parameter()}};
  }

  public static class Parameter {
    @Override
    public String toString() {
      return "same serialized parameter";
    }
  }

  @Test(dataProvider = "colliding", retryAnalyzer = CoordinatedRetry.class)
  @NoAutoRetry
  public void parallel_native_quarantined(Parameter parameter) {
    executions.incrementAndGet();
    throw new AssertionError("both parallel failures must remain quarantined");
  }

  public static class CoordinatedRetry implements IRetryAnalyzer {
    @Override
    public boolean retry(ITestResult result) {
      int invocation = retryCalls.getAndIncrement();
      bothRetrying.countDown();
      await(bothRetrying);
      if (invocation == 1) {
        await(firstReported);
      }
      return false;
    }
  }

  public static class Listener extends TestListenerAdapter {
    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
      suppressed.incrementAndGet();
      firstReported.countDown();
    }

    @Override
    public void onTestFailure(ITestResult result) {
      blocking.incrementAndGet();
      firstReported.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("parallel native retry interleaving timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}

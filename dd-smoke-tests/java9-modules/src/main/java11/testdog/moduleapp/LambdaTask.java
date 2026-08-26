package testdog.moduleapp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Links a lambda {@code Runnable} from inside the application's named module.
 *
 * <p>Lives outside {@code datadog.*} on purpose: the agent skips lambdas declared by classes with
 * that prefix to avoid instrumenting itself, so a task in the application's own {@code
 * datadog.smoketest} package would never exercise this path.
 */
public final class LambdaTask {
  private LambdaTask() {}

  public static void runOnExecutor() throws InterruptedException {
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      final CountDownLatch latch = new CountDownLatch(1);
      pool.execute(latch::countDown);
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("lambda task did not run");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}

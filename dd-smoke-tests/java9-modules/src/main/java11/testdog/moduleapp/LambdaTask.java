package testdog.moduleapp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Links a Runnable lambda from a named module and outside the ignored {@code datadog.*} prefix. */
public final class LambdaTask {
  private static final String FIELD_BACKED_CONTEXT_ACCESSOR =
      "datadog.trace.bootstrap.FieldBackedContextAccessor";

  private LambdaTask() {}

  public static void runOnExecutor() throws InterruptedException {
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      final CountDownLatch latch = new CountDownLatch(1);
      final Runnable task = latch::countDown;
      assertFieldInjection(task, Boolean.getBoolean("dd.trace.lambda.enabled"));
      pool.execute(task);
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("lambda task did not run");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void assertFieldInjection(final Runnable task, final boolean expected) {
    final List<String> interfaces = new ArrayList<>();
    for (final Class<?> type : task.getClass().getInterfaces()) {
      interfaces.add(type.getName());
    }
    final boolean injected = interfaces.contains(FIELD_BACKED_CONTEXT_ACCESSOR);
    if (injected != expected) {
      throw new IllegalStateException(
          "expected lambda field-injection="
              + expected
              + " but was "
              + injected
              + "; "
              + task.getClass().getName()
              + " implements "
              + interfaces);
    }
  }
}

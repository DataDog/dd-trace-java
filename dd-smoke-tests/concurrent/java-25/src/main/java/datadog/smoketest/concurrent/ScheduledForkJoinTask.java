package datadog.smoketest.concurrent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;

/** Exercises delayed context propagation alongside executor and annotation instrumentation. */
public final class ScheduledForkJoinTask implements TestCase {
  @Override
  public void run() throws InterruptedException {
    try (ForkJoinPool pool = new ForkJoinPool(1);
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      CountDownLatch parentFinished = new CountDownLatch(1);
      ScheduledFuture<?> scheduled = schedule(pool, executor, parentFinished);
      // The annotated scheduling method has returned, closing and finishing the parent span.
      parentFinished.countDown();
      try {
        scheduled.get(10, SECONDS);
        pool.submit(this::unrelatedForkJoinTask).get(10, SECONDS);
        executor.submit(this::unrelatedExecutorTask).get(10, SECONDS);
      } catch (ExecutionException | TimeoutException e) {
        throw new AssertionError(e);
      }
    }
  }

  @WithSpan("parent")
  private ScheduledFuture<?> schedule(
      ForkJoinPool pool, ExecutorService executor, CountDownLatch parentFinished) {
    return pool.schedule(
        () -> {
          try {
            if (!parentFinished.await(10, SECONDS)) {
              throw new AssertionError("Parent did not finish");
            }
            scheduledTask(executor);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
        },
        1,
        MILLISECONDS);
  }

  @WithSpan("scheduled")
  private void scheduledTask(ExecutorService executor) throws InterruptedException {
    try {
      executor.submit(this::nestedTask).get(10, SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError(e);
    }
  }

  @WithSpan("nested")
  private void nestedTask() {}

  @WithSpan("unrelated-forkjoin")
  private void unrelatedForkJoinTask() {}

  @WithSpan("unrelated-executor")
  private void unrelatedExecutorTask() {}
}

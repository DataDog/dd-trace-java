package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/** Test ForkJoinPool using the external client API. */
public class DemoForkJoinPoolExternalClient implements FibonacciCalculator {
  private final ForkJoinPool forkJoinPool;

  public DemoForkJoinPoolExternalClient() {
    this.forkJoinPool = new ForkJoinPool();
  }

  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciTask task = new FibonacciTask(n);
    this.forkJoinPool.execute(task);
    return task.result.get();
  }

  private class FibonacciTask implements Runnable {
    private final int n;
    private final CompletableFuture<Long> result;

    public FibonacciTask(int n) {
      this.n = n;
      this.result = new CompletableFuture<>();
    }

    @WithSpan("compute")
    @Override
    public void run() {
      if (this.n <= 1) {
        this.result.complete((long) this.n);
        return;
      }
      FibonacciTask taskOne = new FibonacciTask(this.n - 1);
      forkJoinPool.execute(taskOne);
      FibonacciTask taskTwo = new FibonacciTask(this.n - 2);
      forkJoinPool.submit(taskTwo);

      try {
        this.result.complete(taskOne.result.get() + taskTwo.result.get());
      } catch (InterruptedException | ExecutionException e) {
        this.result.completeExceptionally(e);
      }
    }
  }

  @Override
  public void close() {
    forkJoinPool.shutdown();
  }
}

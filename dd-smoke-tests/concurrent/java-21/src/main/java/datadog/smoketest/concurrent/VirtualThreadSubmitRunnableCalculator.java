package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VirtualThreadSubmitRunnableCalculator implements FibonacciCalculator {
  private final ExecutorService executor;

  public VirtualThreadSubmitRunnableCalculator() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciSubmitTask task = new FibonacciSubmitTask(n);
    this.executor.execute(task);
    return task.result.get();
  }

  @Override
  public void close() {
    this.executor.shutdown();
  }

  public class FibonacciSubmitTask implements Runnable {
    private final long n;
    private final CompletableFuture<Long> result;

    public FibonacciSubmitTask(long n) {
      this.n = n;
      this.result = new CompletableFuture<>();
    }

    @WithSpan("compute")
    public void run() {
      if (this.n <= 1) {
        this.result.complete(this.n);
        return;
      }
      FibonacciSubmitTask task1 = new FibonacciSubmitTask(this.n - 1);
      FibonacciSubmitTask task2 = new FibonacciSubmitTask(this.n - 2);
      executor.submit(task1);
      executor.submit(task2);
      try {
        this.result.complete(task1.result.get() + task2.result.get());
      } catch (InterruptedException | ExecutionException e) {
        this.result.completeExceptionally(e);
      }
    }
  }
}

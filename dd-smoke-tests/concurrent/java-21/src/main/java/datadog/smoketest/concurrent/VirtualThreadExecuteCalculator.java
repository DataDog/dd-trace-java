package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VirtualThreadExecuteCalculator implements FibonacciCalculator {
  private final ExecutorService executor;

  public VirtualThreadExecuteCalculator() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciExecuteTask task = new FibonacciExecuteTask(n);
    this.executor.execute(task);
    return task.result.get();
  }

  @Override
  public void close() {
    this.executor.shutdown();
  }

  public class FibonacciExecuteTask implements Runnable {
    private final long n;
    private final CompletableFuture<Long> result;

    public FibonacciExecuteTask(long n) {
      this.n = n;
      this.result = new CompletableFuture<>();
    }

    @WithSpan("compute")
    public void run() {
      if (this.n <= 1) {
        this.result.complete(this.n);
        return;
      }
      FibonacciExecuteTask task1 = new FibonacciExecuteTask(this.n - 1);
      FibonacciExecuteTask task2 = new FibonacciExecuteTask(this.n - 2);
      executor.execute(task1);
      executor.execute(task2);
      try {
        this.result.complete(task1.result.get() + task2.result.get());
      } catch (InterruptedException | ExecutionException e) {
        this.result.completeExceptionally(e);
      }
    }
  }
}

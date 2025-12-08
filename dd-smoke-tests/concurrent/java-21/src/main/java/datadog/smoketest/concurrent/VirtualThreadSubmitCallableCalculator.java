package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class VirtualThreadSubmitCallableCalculator implements FibonacciCalculator {
  private final ExecutorService executor;

  public VirtualThreadSubmitCallableCalculator() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciSubmitTask task = new FibonacciSubmitTask(n);
    return this.executor.submit(task).get();
  }

  @Override
  public void close() {
    this.executor.shutdown();
  }

  public class FibonacciSubmitTask implements Callable<Long> {
    private final long n;

    public FibonacciSubmitTask(long n) {
      this.n = n;
    }

    @WithSpan("compute")
    public Long call() throws ExecutionException, InterruptedException {
      if (this.n <= 1) {
        return this.n;
      }
      FibonacciSubmitTask task1 = new FibonacciSubmitTask(this.n - 1);
      FibonacciSubmitTask task2 = new FibonacciSubmitTask(this.n - 2);
      Future<Long> future1 = executor.submit(task1);
      Future<Long> future2 = executor.submit(task2);
      return future1.get() + future2.get();
    }
  }
}

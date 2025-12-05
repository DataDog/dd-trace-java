package datadog.smoketest.concurrent;

import static java.util.Set.of;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class VirtualThreadInvokeAllCalculator implements FibonacciCalculator {
  private final ExecutorService executor;

  public VirtualThreadInvokeAllCalculator() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciSubmitTask task = new FibonacciSubmitTask(n);
    return this.executor.invokeAll(of(task)).getFirst().get();
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
      List<Future<Long>> futures = executor.invokeAll(List.of(task1, task2));
      return futures.getFirst().get() + futures.getLast().get();
    }
  }
}

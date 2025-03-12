package datadog.smoketest.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DemoExecutorService implements FibonacciCalculator {
  private final ExecutorService executorService;

  public DemoExecutorService() {
    executorService = Executors.newFixedThreadPool(10);
  }

  @WithSpan("compute")
  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    Future<Long> future = executorService.submit(new FibonacciTask(n));
    return future.get();
  }

  private class FibonacciTask implements Callable<Long> {
    private final int n;

    public FibonacciTask(int n) {
      this.n = n;
    }

    @Override
    public Long call() throws ExecutionException, InterruptedException {
      if (n <= 1) {
        return (long) n;
      }
      return computeFibonacci(n - 1) + computeFibonacci(n - 2);
    }
  }

  @Override
  public void close() {
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(10, SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }
  }
}

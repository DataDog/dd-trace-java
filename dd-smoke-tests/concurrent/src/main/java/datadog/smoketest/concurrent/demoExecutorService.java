package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class demoExecutorService implements FibonacciCalculator {
  private static ExecutorService executorService;

  public demoExecutorService() {
    executorService = Executors.newFixedThreadPool(10);
  }

  @WithSpan
  @Override
  public long computeFibonacci(int n) throws ExecutionException, InterruptedException {
    FibonacciTask task = new FibonacciTask(10);
    Future<Integer> future = executorService.submit(task);
    return future.get();
  }

  private static class FibonacciTask implements Callable<Integer> {
    private final int n;

    public FibonacciTask(int n) {
      this.n = n;
    }

    @WithSpan
    @Override
    public Integer call() {
      if (n <= 1) {
        return n;
      }
      return fibonacci(n);
    }

    private int fibonacci(int n) {
      if (n <= 1) {
        return n;
      }
      return fibonacci(n - 1) + fibonacci(n - 2);
    }
  }

  public void shutdown() {
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      executorService.shutdownNow();
    }
  }

  public static void main(String[] args) throws ExecutionException, InterruptedException {
    demoExecutorService demoService = new demoExecutorService();
    demoService.computeFibonacci(10);
    demoService.shutdown();
  }
}

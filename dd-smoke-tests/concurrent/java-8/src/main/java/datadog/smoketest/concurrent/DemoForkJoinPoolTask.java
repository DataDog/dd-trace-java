package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/** Test ForkJoinPool using the FJP task API. */
public class DemoForkJoinPoolTask implements FibonacciCalculator {
  private final ForkJoinPool forkJoinPool;

  public DemoForkJoinPoolTask() {
    forkJoinPool = new ForkJoinPool();
  }

  @Override
  public long computeFibonacci(int n) {
    return new FibonacciTask(n).invoke();
  }

  private static class FibonacciTask extends RecursiveTask<Long> {
    private final int n;

    public FibonacciTask(int n) {
      this.n = n;
    }

    @WithSpan("compute")
    @Override
    protected Long compute() {
      if (this.n <= 1) {
        return (long) this.n;
      }
      FibonacciTask taskOne = new FibonacciTask(n - 1);
      taskOne.fork();
      FibonacciTask taskTwo = new FibonacciTask(n - 2);
      return taskTwo.compute() + taskOne.join();
    }
  }

  @Override
  public void close() {
    forkJoinPool.shutdown();
  }
}

package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class DemoForkJoin implements FibonacciCalculator {
  private final ForkJoinPool forkJoinPool;

  public DemoForkJoin() {
    forkJoinPool = new ForkJoinPool();
  }

  @WithSpan("compute")
  @Override
  public long computeFibonacci(int n) {
    return forkJoinPool.invoke(new FibonacciTask(n));
  }

  private class FibonacciTask extends RecursiveTask<Long> {
    private final int n;

    public FibonacciTask(int n) {
      this.n = n;
    }

    @Override
    protected Long compute() {
      if (n <= 1) {
        return (long) n;
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

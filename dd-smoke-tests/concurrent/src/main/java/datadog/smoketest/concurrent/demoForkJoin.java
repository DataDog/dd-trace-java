package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class demoForkJoin implements FibonacciCalculator {
  private static ForkJoinPool forkJoinPool;

  public demoForkJoin() {
    forkJoinPool = new ForkJoinPool();
  }

  @WithSpan
  @Override
  public long computeFibonacci(int n) {
    return forkJoinPool.invoke(new FibonacciTask(n));
  }

  private static class FibonacciTask extends RecursiveTask<Long> {
    private final int n;

    public FibonacciTask(int n) {
      this.n = n;
    }

    @WithSpan
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

  public static void shutdown() {
    forkJoinPool.shutdown();
  }

  public static void main(String[] args) {
    demoForkJoin demoService = new demoForkJoin();
    demoService.computeFibonacci(10);
    demoForkJoin.shutdown();
  }
}

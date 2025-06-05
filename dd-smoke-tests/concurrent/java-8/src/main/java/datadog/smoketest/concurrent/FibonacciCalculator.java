package datadog.smoketest.concurrent;

import java.util.concurrent.ExecutionException;

public interface FibonacciCalculator extends AutoCloseable {
  long computeFibonacci(int n) throws ExecutionException, InterruptedException;

  @Override
  void close();
}

package datadog.smoketest.concurrent;

import java.util.concurrent.ExecutionException;

public interface FibonacciCalculator {
  long computeFibonacci(int n) throws ExecutionException, InterruptedException;
}

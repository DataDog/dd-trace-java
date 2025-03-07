package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {

  @WithSpan
  static void spanWrapper(String[] args) throws ExecutionException, InterruptedException {
    // calculate fibonacci using concurrent strategies
    FibonacciCalculator calc = null;
    for (String arg : args) {
      try {
        if (arg.equalsIgnoreCase("executorService")) {
          calc = new DemoExecutorService();
          calc.computeFibonacci(10);
        } else if (arg.equalsIgnoreCase("forkJoin")) {
          calc = new DemoForkJoin();
          calc.computeFibonacci(10);
        }
      } finally {
        calc.close();
      }
    }
  }

  public static void main(String[] args) throws InterruptedException, ExecutionException {
    // wrap calculations in a span
    spanWrapper(args);
  }
}

package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {

  @WithSpan
  static void spanWrapper(String[] args) throws ExecutionException, InterruptedException {
    // calculate fibonacci using concurrent strategies
    FibonacciCalculator calc;
    for (String arg : args) {
      if (arg.equalsIgnoreCase("executorService")) {
        calc = new demoExecutorService();
        calc.computeFibonacci(10);
      } else if (arg.equalsIgnoreCase("forkJoin")) {
        calc = new demoForkJoin();
        calc.computeFibonacci(10);
      }
    }
  }

  public static void main(String[] args) throws InterruptedException, ExecutionException {
    // wrap calculations in a span
    spanWrapper(args);

    System.exit(0);
  }
}

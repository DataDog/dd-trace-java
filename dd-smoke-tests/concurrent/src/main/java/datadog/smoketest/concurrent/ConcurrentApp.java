package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {
  @WithSpan
  static void computeFibonacciHelper(String[] args)
      throws ExecutionException, InterruptedException {
    FibonacciCalculator calc;
    for (String arg : args) {
      if (arg.equalsIgnoreCase("executorService")) {
        calc = new demoExecutorService();
        long result = calc.computeFibonacci(10);
        System.out.println("=====ExecutorService result: " + result + "=====");
      } else if (arg.equalsIgnoreCase("forkJoin")) {
        calc = new demoForkJoin();
        long result = calc.computeFibonacci(10);
        System.out.println("=====ForkJoin result: " + result + "=====");
      }
    }
  }

  public static void main(String[] args) throws InterruptedException, ExecutionException {
    System.out.println("=====ConcurrentApp start=====");

    // do fibonacci calculation
    computeFibonacciHelper(args);

    // add custom spans here / elsewhere?

    System.out.println("=====ConcurrentApp finish=====");
    System.exit(0);
  }
}

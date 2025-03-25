package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {
  @WithSpan("main")
  public static void main(String[] args) {
    // Calculate fibonacci using concurrent strategies
    for (String arg : args) {
      try (FibonacciCalculator calc = getCalculator(arg)) {
        calc.computeFibonacci(10);
      } catch (ExecutionException | InterruptedException e) {
        throw new RuntimeException("Failed to compute fibonacci number.", e);
      }
    }
  }

  private static FibonacciCalculator getCalculator(String name) {
    if (name.equalsIgnoreCase("executorService")) {
      return new DemoExecutorService();
    } else if (name.equalsIgnoreCase("forkJoinPoolTask")) {
      return new DemoForkJoinPoolTask();
    } else if (name.equalsIgnoreCase("forkJoinPoolExternalClient")) {
      return new DemoForkJoinPoolExternalClient();
    }
    throw new IllegalArgumentException("Unknown calculator: " + name);
  }
}

package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {
  @WithSpan("main")
  public static void main(String[] args) {
    for (String arg : args) {
      try (FibonacciCalculator calc = getCalculator(arg)) {
        calc.computeFibonacci(10);
      } catch (ExecutionException | InterruptedException e) {
        throw new RuntimeException("Failed to compute fibonacci number.", e);
      }
    }
  }

  private static FibonacciCalculator getCalculator(String name) {
    return switch (name) {
      case "virtualThreadStart" -> new VirtualThreadStartCalculator();
      case "virtualThreadExecute" -> new VirtualThreadExecuteCalculator();
      case "virtualThreadSubmitRunnable" -> new VirtualThreadSubmitRunnableCalculator();
      case "virtualThreadSubmitCallable" -> new VirtualThreadSubmitCallableCalculator();
      case "virtualThreadInvokeAll" -> new VirtualThreadInvokeAllCalculator();
      case "virtualThreadInvokeAny" -> new VirtualThreadInvokeAnyCalculator();
      default -> throw new RuntimeException("Unknown Fibonacci calculator: " + name);
    };
  }
}

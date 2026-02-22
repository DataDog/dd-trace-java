package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {
  public static void main(String[] args) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      while (true) {
        String signal = reader.readLine();
        if (signal == null || "exit".equals(signal)) {
          return;
        }
        runScenario(signal);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to run concurrent smoke test scenario.", e);
    }
  }

  @WithSpan("main")
  private static void runScenario(String signal) throws ExecutionException, InterruptedException {
    try (FibonacciCalculator calc = getCalculator(signal)) {
      calc.computeFibonacci(10);
    }
  }

  private static FibonacciCalculator getCalculator(String signal) {
    return switch (signal) {
      case "virtualThreadStart" -> new VirtualThreadStartCalculator();
      case "virtualThreadExecute" -> new VirtualThreadExecuteCalculator();
      case "virtualThreadSubmitRunnable" -> new VirtualThreadSubmitRunnableCalculator();
      case "virtualThreadSubmitCallable" -> new VirtualThreadSubmitCallableCalculator();
      case "virtualThreadInvokeAll" -> new VirtualThreadInvokeAllCalculator();
      case "virtualThreadInvokeAny" -> new VirtualThreadInvokeAnyCalculator();
      default -> throw new RuntimeException("Unknown scenario signal: " + signal);
    };
  }
}

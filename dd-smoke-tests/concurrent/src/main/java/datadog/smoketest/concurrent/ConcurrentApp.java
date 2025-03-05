package datadog.smoketest.concurrent;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ExecutionException;

public class ConcurrentApp {

  @WithSpan
  static void startSpan() {
    System.out.println("=====startSpan=====");
  }

  public static void main(String[] args) throws InterruptedException, ExecutionException {
    System.out.println("=====ConcurrentApp start=====");

    // start parent span
    startSpan();

    // get an Open Telemetry tracer
    //    Tracer tracer =
    // GlobalOpenTelemetry.getTracerProvider().tracerBuilder("smoketests").build();

    // do fibonacci calculation
    FibonacciCalculator calc;
    if (args.length > 0) {
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

    // demo custom spans
    //    for (int i = 0; i < 10; i++) {
    //      Span span = tracer.spanBuilder("span-" + i).startSpan();
    //      Thread.sleep(20);
    //      span.end();
    //    }

    // demo something else?

    System.out.println("=====ConcurrentApp finish=====");
    System.exit(0);
  }
}

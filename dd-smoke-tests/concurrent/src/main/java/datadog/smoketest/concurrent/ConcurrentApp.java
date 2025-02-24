package datadog.smoketest.concurrent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;

public class ConcurrentApp {

  @WithSpan
  static void startSpan() {
    System.out.println("=====startSpan=====");
  }

  public static void main(String[] args) throws InterruptedException {
    System.out.println("=====ConcurrentApp start=====");

    // start parent span
    startSpan();

    // get an Open Telemetry tracer
    Tracer tracer = GlobalOpenTelemetry.getTracerProvider().tracerBuilder("smoketests").build();

    // demo ExecutorService
    demoExecutorService.main(args);

    // demo ForkJoin
    demoForkJoin.main(args);

    // demo custom spans
    for (int i = 0; i < 10; i++) {
      Span span = tracer.spanBuilder("span-" + i).startSpan();
      Thread.sleep(20);
      span.end();
    }

    // demo something else?

    System.out.println("=====ConcurrentApp finish=====");
  }
}

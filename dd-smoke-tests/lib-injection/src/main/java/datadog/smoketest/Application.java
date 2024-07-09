package datadog.smoketest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/** This application is a minimalistic application to create spans. */
public class Application {
  public static void main(String[] args) throws InterruptedException {
    // Get an Open Telemetry tracer
    Tracer tracer = GlobalOpenTelemetry.getTracerProvider().tracerBuilder("smoketests").build();
    // Create a trace with few spans
    for (int i = 0; i < 10; i++) {
      Span span = tracer.spanBuilder("span-" + i).startSpan();
      Thread.sleep(20);
      span.end();
    }
  }
}

package datadog.smoketest.opentelemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;

/**
 * This application is a minimalistic application to create a trace with Open Telemetry Tracer API.
 */
public class Application {

  @WithSpan
  static void annotatedSpan() {
    Span.current().addEvent("annotated-span-event");
  }

  public static void main(String[] args) throws InterruptedException {
    // Start trace without touching GlobalOpenTelemetry
    annotatedSpan();

    // Get an Open Telemetry tracer
    Tracer tracer = GlobalOpenTelemetry.getTracerProvider().tracerBuilder("smoketests").build();

    // Start some manual traces
    for (int i = 0; i < 10; i++) {
      Span span = tracer.spanBuilder("span-" + i).startSpan();
      Thread.sleep(20);
      span.end();
    }
  }
}

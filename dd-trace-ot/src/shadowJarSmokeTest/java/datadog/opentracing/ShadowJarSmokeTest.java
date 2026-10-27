package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Smoke tests for the shaded OpenTracing distribution and its public dependencies. */
class ShadowJarSmokeTest {
  @ParameterizedTest
  @ValueSource(strings = {"datadog", "tracecontext", "datadog,tracecontext"})
  void injectAndExtractWithoutLlmObservability(String propagationStyle) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("writer.type", "LoggingWriter");
    properties.setProperty("llmobs.enabled", "false");
    properties.setProperty("trace.propagation.style.inject", propagationStyle);
    properties.setProperty("trace.propagation.style.extract", propagationStyle);

    try (DDTracer tracer =
        DDTracer.builder()
            .serviceName("shadow-jar-smoke-test")
            .withProperties(properties)
            .build()) {
      Span span = tracer.buildSpan("propagate").start();
      try {
        Map<String, String> headers = new HashMap<>();
        TextMapAdapter carrier = new TextMapAdapter(headers);
        tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, carrier);

        if (propagationStyle.contains("datadog")) {
          assertEquals(span.context().toTraceId(), headers.get("x-datadog-trace-id"));
          assertEquals(span.context().toSpanId(), headers.get("x-datadog-parent-id"));
          assertTrue(headers.containsKey("x-datadog-tags"));
        }
        if (propagationStyle.contains("tracecontext")) {
          assertTrue(headers.containsKey("traceparent"));
          assertTrue(headers.containsKey("tracestate"));
        }

        SpanContext extracted = tracer.extract(Format.Builtin.HTTP_HEADERS, carrier);
        assertEquals(span.context().toTraceId(), extracted.toTraceId());
        assertEquals(span.context().toSpanId(), extracted.toSpanId());
      } finally {
        span.finish();
      }
    }
  }
}

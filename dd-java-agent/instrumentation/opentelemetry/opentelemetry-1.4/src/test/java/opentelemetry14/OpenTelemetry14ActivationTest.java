package opentelemetry14;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

// Explicitly clear the instrumentation activation from AbstractOpenTelemetry14Test
@WithConfig(key = "integration.opentelemetry.experimental.enabled", value = "")
abstract class OpenTelemetry14ActivationTest extends AbstractOpenTelemetry14Test {

  abstract boolean shouldBeInjected();

  @Test
  void testInstrumentationInjection() {
    Tracer tracer = GlobalOpenTelemetry.get().getTracerProvider().get("some-instrumentation");
    SpanBuilder builder = tracer.spanBuilder("some-name");
    Span result = builder.startSpan();
    Context context = Context.current();

    if (shouldBeInjected()) {
      assertImplementation(tracer, "OtelTracer");
      assertImplementation(builder, "OtelSpanBuilder");
      assertImplementation(result, "OtelSpan");
      assertImplementation(context, "OtelContext");
    } else {
      assertImplementation(tracer, "DefaultTracer");
      assertImplementation(context, "ArrayBasedContext");
    }
  }

  private static void assertImplementation(Object instance, String expectedClassName) {
    String actualClassName = instance.getClass().getName();
    assertTrue(
        actualClassName.endsWith("." + expectedClassName),
        "Expected " + expectedClassName + " but got " + actualClassName);
  }
}

// Forked test variants: each runs in its own JVM to allow GlobalOpenTelemetry static state to reset

@WithConfig(key = "integration.opentelemetry.experimental.enabled", value = "true")
class OpenTelemetry14ActivationByInstrumentationNameForkedTest
    extends OpenTelemetry14ActivationTest {
  @Override
  boolean shouldBeInjected() {
    return true;
  }
}

@WithConfig(key = "trace.otel.enabled", value = "true")
class OpenTelemetry14ActivationByOtelRfcNameForkedTest extends OpenTelemetry14ActivationTest {
  @Override
  boolean shouldBeInjected() {
    return true;
  }
}

class OpenTelemetry14DisableByDefaultForkedTest extends OpenTelemetry14ActivationTest {
  @Override
  boolean shouldBeInjected() {
    return false;
  }
}

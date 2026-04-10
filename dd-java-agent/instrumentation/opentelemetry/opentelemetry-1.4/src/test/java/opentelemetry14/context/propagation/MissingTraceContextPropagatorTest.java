package opentelemetry14.context.propagation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import opentelemetry14.AbstractOpenTelemetry14Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MissingTraceContextPropagatorTest extends AbstractOpenTelemetry14Test {

  static Stream<Arguments> testExtractOnMissingTracecontextArguments() {
    return Stream.of(
        arguments(
            "agent propagator", GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()),
        arguments("W3C propagator", W3CTraceContextPropagator.getInstance()));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("testExtractOnMissingTracecontextArguments")
  void testExtractOnMissingTracecontext(String scenario, TextMapPropagator propagator) {
    Map<String, String> headers = new HashMap<>();
    headers.put("User-Agent", "test");

    Context context = propagator.extract(Context.root(), headers, TextMap.INSTANCE);
    Span extractedSpan = Span.fromContext(context);

    assertNotNull(extractedSpan);
    assertFalse(extractedSpan.getSpanContext().isValid());
    assertNull(Span.fromContextOrNull(context));
  }
}

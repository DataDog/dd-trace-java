package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_INTERNAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.camel.Exchange;
import org.apache.camel.SafeCopyProperty;

abstract class AbstractCamelTest extends AbstractInstrumentationTest {
  static final String STATE_KEY = "datadog.trace.instrumentation.camel.CamelState";

  static SafeCopyProperty routeContext(Exchange exchange) {
    return exchange.getExchangeExtension().getSafeCopyProperty(STATE_KEY, SafeCopyProperty.class);
  }

  final DDSpan routeSpan(List<DDSpan> spans, String resource) {
    return spans.stream()
        .filter(span -> resource.contentEquals(span.getResourceName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing route " + resource));
  }

  final List<DDSpan> routeSpans(int count) {
    blockUntilTracesMatch(traces ->
        traces.stream()
                .flatMap(List::stream)
                .filter(span -> "camel.route".contentEquals(span.getOperationName()))
                .count()
            >= count);
    List<DDSpan> spans =
        writer.stream()
            .flatMap(List::stream)
            .filter(span -> "camel.route".contentEquals(span.getOperationName()))
            .collect(Collectors.toList());
    for (DDSpan span : spans) {
      assertEquals(SPAN_KIND_INTERNAL, span.getTag(SPAN_KIND));
      assertTrue(span.isMeasured(), "nested and asynchronous route spans must contribute metrics");
      assertNull(span.getSpanType(), "route orchestration has no transport span type");
    }
    return spans;
  }
}

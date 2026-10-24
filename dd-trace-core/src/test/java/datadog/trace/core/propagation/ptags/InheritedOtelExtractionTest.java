package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.PropagationTags;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Real extraction leaves the {@code ot} member unparsed until something reads it. */
class InheritedOtelExtractionTest extends DDCoreJavaSpecification {
  private static final String TRACE_PARENT =
      "00-00000000000000000000000000000001-123456789abcdef0-01";
  private static final String OTEL_MEMBER = "ot=rv:ef284ace7a91e1;th:e6666666666668";

  @Test
  void leafFromDatadogUpstreamNeverParses() {
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "1");
    headers.put("x-datadog-parent-id", "2");
    headers.put("x-datadog-sampling-priority", "1");
    headers.put("traceparent", TRACE_PARENT);
    headers.put("tracestate", "dd=s:1;p:123456789abcdef0," + OTEL_MEMBER);

    PropagationTags tags = extractAndFinishLeaf(headers);

    assertUnparsed(tags);
    assertTrue(tags.headerValue(W3C).contains(OTEL_MEMBER));
  }

  @Test
  void leafFromOtelUpstreamNeverParses() {
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", TRACE_PARENT);
    headers.put("tracestate", OTEL_MEMBER);

    PropagationTags tags = extractAndFinishLeaf(headers);

    assertUnparsed(tags);
    assertTrue(tags.headerValue(W3C).contains(OTEL_MEMBER));
  }

  @Test
  void inconsistentInheritedThresholdIsStillDropped() {
    // rv < th means drop, but the upstream kept the trace: the threshold can't be forwarded
    Map<String, String> headers = new HashMap<>();
    headers.put("traceparent", TRACE_PARENT);
    headers.put("tracestate", "ot=rv:0000000000000a;th:8");

    PropagationTags tags = extractAndFinishLeaf(headers);

    assertUnparsed(tags);
    String outbound = tags.headerValue(W3C);
    assertTrue(outbound.contains("ot=rv:0000000000000a"));
    assertFalse(outbound.contains("th:8"));
  }

  private PropagationTags extractAndFinishLeaf(Map<String, String> headers) {
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract())
        .thenReturn(new LinkedHashSet<>(asList(DATADOG, TRACECONTEXT)));
    when(config.getxDatadogTagsMaxLength()).thenReturn(DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);
    when(config.isApmTracingEnabled()).thenReturn(true);

    CoreTracer tracer = tracerBuilder().build();
    try {
      HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, tracer::captureTraceConfig);
      ExtractedContext extracted =
          assertInstanceOf(ExtractedContext.class, extractor.extract(headers, stringValuesMap()));
      AgentSpan span = tracer.buildSpan("test", "leaf").asChildOf(extracted).start();
      span.finish();
      return extracted.getPropagationTags();
    } finally {
      tracer.close();
    }
  }

  private static void assertUnparsed(PropagationTags tags) {
    InheritedSamplingState state =
        assertInstanceOf(InheritedSamplingState.class, tags.samplingState());
    assertFalse(
        ((PTagsFactory.PTags) tags).inheritedOtelMember().isResolved(state.pendingChecks()),
        "a leaf must not parse the inherited otel member");
  }
}

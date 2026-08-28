package datadog.opentracing;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.Scope;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

// This test focuses on things that are different between OpenTracing API 0.32.0 and 0.33.0
class OT33ApiTest extends DDJavaSpecification {

  private final ListWriter writer = new ListWriter();
  private final Tracer tracer = DDTracer.builder().writer(writer).build();

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      ((DDTracer) tracer).close();
    }
  }

  @Test
  void testStart() throws Exception {
    io.opentracing.Span span = tracer.buildSpan("some name").start();
    Scope scope = tracer.activateSpan(span);
    scope.close();

    assertFalse(((datadog.trace.core.DDSpan) ((OTSpan) span).getDelegate()).isFinished());
    assertEquals(0, writer.size());

    span.finish();

    writer.waitForTraces(1);
    assertEquals(1, writer.size());
    assertEquals("some name", writer.get(0).get(0).getOperationName());
  }

  @Test
  void testScopeManager() {
    datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI coreTracer =
        ((DDTracer) tracer).getInternalTracer();

    io.opentracing.Span span = tracer.buildSpan("some name").start();
    Scope scope = tracer.scopeManager().activate(span);

    assertEquals(span, tracer.activeSpan());
    assertEquals(((OTSpan) span).getDelegate(), coreTracer.activeSpan());

    scope.close();
    span.finish();
  }

  @ParameterizedTest
  @TableTest({
    "scenario     | contextPriority               | samplingMechanism         | propagatedPriority           ",
    "sampler drop | PrioritySampling.SAMPLER_DROP | SamplingMechanism.DEFAULT | PrioritySampling.SAMPLER_DROP",
    "sampler keep | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.DEFAULT | PrioritySampling.SAMPLER_KEEP",
    "unset        | PrioritySampling.UNSET        | SamplingMechanism.DEFAULT | PrioritySampling.SAMPLER_KEEP",
    "user keep    | PrioritySampling.USER_KEEP    | SamplingMechanism.MANUAL  | PrioritySampling.USER_KEEP   ",
    "user drop    | PrioritySampling.USER_DROP    | SamplingMechanism.MANUAL  | PrioritySampling.USER_DROP   "
  })
  void testInjectExtract(
      String scenario,
      @ConvertWith(PrioritySamplingConverter.class) byte contextPriority,
      @ConvertWith(SamplingMechanismConverter.class) byte samplingMechanism,
      @ConvertWith(PrioritySamplingConverter.class) byte propagatedPriority)
      throws Exception {
    io.opentracing.Span span = tracer.buildSpan("some name").start();
    io.opentracing.SpanContext context = span.context();
    Map<String, String> map = new HashMap<>();
    LocalTextMapAdapter adapter = new LocalTextMapAdapter(map);

    DDSpanContext ddContext = (DDSpanContext) ((OTSpanContext) context).getDelegate();
    ddContext.setSamplingPriority(contextPriority, samplingMechanism);
    tracer.inject(context, Format.Builtin.TEXT_MAP, adapter);

    DDTraceId traceId = ((OTSpan) span).getDelegate().spanContext().getTraceId();
    long spanId = ((OTSpan) span).getDelegate().spanContext().getSpanId();
    String expectedTraceparent =
        "00-"
            + traceId.toHexStringPadded(32)
            + "-"
            + DDSpanId.toHexStringPadded(spanId)
            + "-"
            + (propagatedPriority > 0 ? "01" : "00");
    int effectiveSamplingMechanism = contextPriority == UNSET ? AGENT_RATE : samplingMechanism;
    String expectedTracestate =
        "dd=s:"
            + propagatedPriority
            + ";p:"
            + DDSpanId.toHexStringPadded(spanId)
            + (propagatedPriority > 0 ? ";t.dm:-" + effectiveSamplingMechanism : "")
            + ";t.tid:"
            + traceId.toHexStringPadded(32).substring(0, 16)
            + (contextPriority == UNSET ? ";t.ksr:1" : "");

    Map<String, String> expectedTextMap = new HashMap<>();
    expectedTextMap.put("x-datadog-trace-id", context.toTraceId());
    expectedTextMap.put("x-datadog-parent-id", context.toSpanId());
    expectedTextMap.put("x-datadog-sampling-priority", String.valueOf(propagatedPriority));
    expectedTextMap.put("traceparent", expectedTraceparent);
    expectedTextMap.put("tracestate", expectedTracestate);

    ArrayList<String> datadogTags = new ArrayList<>();
    if (propagatedPriority > 0) {
      datadogTags.add("_dd.p.dm=-" + effectiveSamplingMechanism);
    }
    if (traceId.toHighOrderLong() != 0) {
      datadogTags.add(
          "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16));
    }
    if (contextPriority == UNSET) {
      datadogTags.add("_dd.p.ksr=1");
    }
    if (!datadogTags.isEmpty()) {
      expectedTextMap.put("x-datadog-tags", String.join(",", datadogTags));
    }

    assertEquals(expectedTextMap, map);

    io.opentracing.SpanContext extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter);
    assertEquals(context.toTraceId(), extract.toTraceId());
    assertEquals(context.toSpanId(), extract.toSpanId());
    assertEquals(propagatedPriority, ((OTSpanContext) extract).getDelegate().getSamplingPriority());
  }

  static class LocalTextMapAdapter implements TextMap {
    private final Map<String, String> map;

    LocalTextMapAdapter(Map<String, String> map) {
      this.map = map;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      return map.entrySet().iterator();
    }

    @Override
    public void put(String key, String value) {
      map.put(key, value);
    }
  }
}

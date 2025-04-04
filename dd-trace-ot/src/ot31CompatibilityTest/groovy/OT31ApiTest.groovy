import datadog.opentracing.DDTracer
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.internal.util.LongStringUtils
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import io.opentracing.Tracer
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import spock.lang.Subject

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL

// This test focuses on things that are different between OpenTracing API 0.31.0 and 0.32.0
class OT31ApiTest extends DDSpecification {
  def writer = new ListWriter()

  @Subject
  Tracer tracer = DDTracer.builder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "test startActive"() {
    when:
    def scope = tracer.buildSpan("some name").startActive(finishSpan)
    scope.close()

    then:
    (scope.span().delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan << [true, false]
  }

  def "test startManual"() {
    when:
    tracer.buildSpan("some name").startManual().finish()

    then:
    assertTraces(writer, 1) {
      trace(1) {
        basicSpan(it, "some name")
      }
    }
  }

  def "test scopemanager"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span, finishSpan)

    expect:
    scope != null
    tracer.scopeManager().active().span() == span

    when: "attempting to close the span this way doesn't work because we lost the 'finishSpan' reference"
    tracer.scopeManager().active().close()

    then:
    !(span.delegate as DDSpan).isFinished()

    when:
    scope.close()

    then:
    (span.delegate as DDSpan).isFinished() == finishSpan

    where:
    finishSpan << [true, false]
  }

  def "test inject extract"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    def context = span.context()
    def textMap = [:]
    def adapter = new TextMapAdapter(textMap)

    when:
    context.delegate.setSamplingPriority(contextPriority, samplingMechanism)
    tracer.inject(context, Format.Builtin.TEXT_MAP, adapter)

    then:
    def traceId = span.delegate.context.traceId as DDTraceId
    def spanId = span.delegate.context.spanId
    def expectedTraceparent = "00-${traceId.toHexStringPadded(32)}" +
      "-${DDSpanId.toHexStringPadded(spanId)}" +
      "-" + (propagatedPriority > 0 ? "01" : "00")
    def effectiveSamplingMechanism = contextPriority == UNSET ? AGENT_RATE : samplingMechanism
    def expectedTracestate = "dd=s:${propagatedPriority};p:${DDSpanId.toHexStringPadded(spanId)}" +
      (propagatedPriority > 0 ? ";t.dm:-" + effectiveSamplingMechanism : "") +
      ";t.tid:${traceId.toHexStringPadded(32).substring(0, 16)}"
    def expectedTextMap = [
      "x-datadog-trace-id"         : context.toTraceId(),
      "x-datadog-parent-id"        : context.toSpanId(),
      "x-datadog-sampling-priority": propagatedPriority.toString(),
      "traceparent"                : expectedTraceparent,
      "tracestate"                 : expectedTracestate,
    ]
    def datadogTags = []
    if (propagatedPriority > 0) {
      datadogTags << "_dd.p.dm=-$effectiveSamplingMechanism"
    }
    if (traceId.toHighOrderLong() != 0) {
      datadogTags << "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16)
    }
    if (!datadogTags.empty) {
      expectedTextMap.put("x-datadog-tags", datadogTags.join(','))
    }
    textMap == expectedTextMap

    when:
    def extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter)

    then:
    extract.toTraceId() == context.toTraceId()
    extract.toSpanId() == context.toSpanId()
    extract.delegate.samplingPriority == propagatedPriority

    where:
    contextPriority | samplingMechanism | propagatedPriority
    SAMPLER_DROP    | DEFAULT           | SAMPLER_DROP
    SAMPLER_KEEP    | DEFAULT           | SAMPLER_KEEP
    UNSET           | DEFAULT           | SAMPLER_KEEP
    USER_KEEP       | MANUAL            | USER_KEEP
    USER_DROP       | MANUAL            | USER_DROP
  }

  static class TextMapAdapter implements TextMap {
    private final Map<String, String> map

    TextMapAdapter(Map<String, String> map) {
      this.map = map
    }

    @Override
    Iterator<Map.Entry<String, String>> iterator() {
      return map.entrySet().iterator()
    }

    @Override
    void put(String key, String value) {
      map.put(key, value)
    }
  }
}

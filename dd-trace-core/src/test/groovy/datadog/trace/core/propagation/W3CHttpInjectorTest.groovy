package datadog.trace.core.propagation

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY
import static datadog.trace.core.propagation.W3CHttpCodec.newInjector


class W3CHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector = newInjector(["some-baggage-key":"SOME_CUSTOM_HEADER"])

  def "inject http headers"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from(traceId),
      DDSpanId.from(spanId),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      samplingPriority,
      origin,
      ["k1" : "v1", "k2" : "v2","some-baggage-key": "some-value"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"))
    final Map<String, String> carrier = [:]
    Map<String, String> expected = [
      (TRACE_PARENT_KEY)        : buildTraceParent(traceId, spanId, samplingPriority),
      (TRACE_STATE_KEY)         : tracestate,
      (OT_BAGGAGE_PREFIX + "k1"): "v1",
      (OT_BAGGAGE_PREFIX + "k2"): "v2",
      "SOME_CUSTOM_HEADER"      : "some-value",
    ]

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    carrier == expected

    cleanup:
    tracer.close()

    where:
    traceId               | spanId                | samplingPriority | origin   | tracestate
    "1"                   | "2"                   | UNSET            | null     | "dd=p:0000000000000002;t.usr:123"
    "1"                   | "4"                   | SAMPLER_KEEP     | "saipan" | "dd=s:1;o:saipan;p:0000000000000004;t.usr:123"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | UNSET            | "saipan" | "dd=o:saipan;p:fffffffffffffffe;t.usr:123"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | SAMPLER_KEEP     | null     | "dd=s:1;p:ffffffffffffffff;t.usr:123"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | SAMPLER_DROP     | null     | "dd=s:0;p:ffffffffffffffff;t.usr:123"
  }

  def "inject http headers with end-to-end"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=-4,_dd.p.anytag=value"))

    mockedContext.beginEndToEnd()

    final Map<String, String> carrier = [:]

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    carrier == [
      (TRACE_PARENT_KEY): buildTraceParent('1', '2', UNSET),
      (TRACE_STATE_KEY): "dd=o:fakeOrigin;p:0000000000000002;t.dm:-4;t.anytag:value",
      (OT_BAGGAGE_PREFIX + "t0"): "${(long) (mockedContext.endToEndStartTime / 1000000L)}",
      (OT_BAGGAGE_PREFIX + "k1"): "v1",
      (OT_BAGGAGE_PREFIX + "k2"): "v2",
    ]

    cleanup:
    tracer.close()
  }

  def "inject the decision maker tag"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final DDSpanContext mockedContext =
      new DDSpanContext(
      DDTraceId.from("1"),
      DDSpanId.from("2"),
      DDSpanId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      UNSET,
      "fakeOrigin",
      ["k1" : "v1", "k2" : "v2"],
      false,
      "fakeType",
      0,
      tracer.traceCollectorFactory.create(DDTraceId.ONE),
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())

    mockedContext.setSamplingPriority(USER_KEEP, MANUAL)

    final Map<String, String> carrier = [:]

    when:
    injector.inject(mockedContext, carrier, MapSetter.INSTANCE)

    then:
    carrier == [
      (TRACE_PARENT_KEY): buildTraceParent('1', '2', USER_KEEP),
      (TRACE_STATE_KEY): "dd=s:2;o:fakeOrigin;p:0000000000000002;t.dm:-4",
      (OT_BAGGAGE_PREFIX + "k1"): "v1",
      (OT_BAGGAGE_PREFIX + "k2"): "v2",
    ]

    cleanup:
    tracer.close()
  }

  def "update last parent id on child span"() {
    setup:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()
    final Map<String, String> carrier = [:]

    when: 'injecting root span context'
    def rootSpan = tracer.startSpan('test', 'root')
    def rootSpanId = rootSpan.spanId
    def rootScope = tracer.activateSpan(rootSpan)

    injector.inject(rootSpan.context() as DDSpanContext, carrier, MapSetter.INSTANCE)
    def lastParentId = extractLastParentId(carrier)

    then: 'trace state has root span id as last parent'
    lastParentId == rootSpanId

    when: 'injecting child span context'
    def childSpan = tracer.startSpan('test', 'child')
    def childSpanId = childSpan.spanId
    carrier.clear()
    injector.inject(childSpan.context() as DDSpanContext, carrier, MapSetter.INSTANCE)
    lastParentId = extractLastParentId(carrier)

    then: 'trace state has child span id as last parent'
    lastParentId == childSpanId

    when: 'injecting root span again'
    childSpan.finish()
    carrier.clear()
    injector.inject(rootSpan.context() as DDSpanContext, carrier, MapSetter.INSTANCE)
    lastParentId = extractLastParentId(carrier)

    then: 'trace state has root span is as last parent again'
    lastParentId == rootSpanId

    cleanup:
    rootScope.close()
    rootSpan.finish()
  }

  static String buildTraceParent(String traceId, String spanId, int samplingPriority) {
    return "00-${DDTraceId.from(traceId).toHexString()}-${DDSpanId.toHexStringPadded(DDSpanId.from(spanId))}-${samplingPriority > 0 ? '01': '00'}"
  }

  static long extractLastParentId(Map<String, String> carrier) {
    def traceState = carrier[TRACE_STATE_KEY]
    def traceStateMembers = traceState.split(',')
    def ddTraceStateMember = traceStateMembers.find { it.startsWith("dd=")}.substring(3)
    def parts = ddTraceStateMember.split(';')
    def spanIdHex = parts.find { it.startsWith('p:')}.substring(2)
    DDSpanId.fromHex(spanIdHex)
  }
}

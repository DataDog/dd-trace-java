package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.config.TracerConfig
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

class DatadogHttpExtractorTest extends DDSpecification {

  private HttpCodec.Extractor _extractor

  private HttpCodec.Extractor getExtractor() {
    _extractor ?: (
      _extractor = DatadogHttpCodec.newExtractor(["SOME_HEADER": "some-tag"])
      )
  }

  boolean origAppSecActive

  void setup() {
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true

    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true")
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive
  }

  def "extract http headers"() {
    setup:
    def headers = [
      ""                                      : "empty key",
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    if (samplingPriority != PrioritySampling.UNSET) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    if (origin) {
      headers.put(ORIGIN_KEY, origin)
    }

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDId.from(traceId)
    context.spanId == DDId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    where:
    traceId               | spanId                | samplingPriority              | origin
    "1"                   | "2"                   | PrioritySampling.UNSET        | null
    "2"                   | "3"                   | PrioritySampling.SAMPLER_KEEP | "saipan"
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.UNSET        | "saipan"
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | "saipan"
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]
    if (headers.containsKey(ORIGIN_KEY)) {
      assert ((TagContext) context).origin == "my-origin"
    }

    where:
    headers << [
      [SOME_HEADER: "my-interesting-info"],
      [(ORIGIN_KEY): "my-origin", SOME_HEADER: "my-interesting-info"],
    ]
  }

  def "extract headers with forwarding"() {
    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    !(context instanceof ExtractedContext)
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "1234"
    tagOnlyCtx = [
      "Forwarded" : "for=$forwardedIp:$forwardedPort"
    ]
    fullCtx = [
      (TRACE_ID_KEY.toUpperCase()): 1,
      (SPAN_ID_KEY.toUpperCase()) : 2,
      "Forwarded" : "for=$forwardedIp:$forwardedPort"
    ]
  }

  def "extract headers with x-forwarding"() {
    setup:
    String forwardedIp = '1.2.3.4'
    String forwardedPort = '1234'
    def tagOnlyCtx = [
      "X-Forwarded-For" : forwardedIp,
      "X-Forwarded-Port": forwardedPort
    ]
    def fullCtx = [
      (TRACE_ID_KEY.toUpperCase()): 1,
      (SPAN_ID_KEY.toUpperCase()) : 2,
      "x-forwarded-for"           : forwardedIp,
      "x-forwarded-port"          : forwardedPort
    ]

    when:
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    context instanceof TagContext
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap()) == null
  }

  void 'extract headers with ip resolution disabled'() {
    setup:
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED, 'false')

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'User-agent': 'foo/bar',
    ]

    when:
    TagContext ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == null
    ctx.userAgent == 'foo/bar'
  }


  void 'extract headers with ip resolution disabled — appsec disabled variant'() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = false

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'User-agent': 'foo/bar',
    ]

    when:
    TagContext ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == null
  }

  void 'custom IP header collection does not disable standard ip header collection'() {
    setup:
    injectSysConfig(TracerConfig.TRACE_CLIENT_IP_HEADER, "my-header")

    def tagOnlyCtx = [
      'X-Forwarded-For': '::1',
      'My-Header': '8.8.8.8',
    ]

    when:
    def ctx = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    ctx != null
    ctx.XForwardedFor == '::1'
    ctx.customIpHeader == '8.8.8.8'
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "traceId",
      (SPAN_ID_KEY.toUpperCase())             : "spanId",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null
  }

  def "extract http headers with out of range trace ID"() {
    setup:
    String outOfRangeTraceId = (TRACE_ID_MAX + 1).toString()
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : outOfRangeTraceId,
      (SPAN_ID_KEY.toUpperCase())             : "0",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null
  }

  def "extract http headers with out of range span ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : "0",
      (SPAN_ID_KEY.toUpperCase())             : "-1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null
  }

  def "more ID range validation"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase()): traceId,
      (SPAN_ID_KEY.toUpperCase()) : spanId,
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.spanId == expectedSpanId
    } else {
      assert context == null
    }

    where:
    traceId               | spanId                | expectedTraceId | expectedSpanId
    "-1"                  | "1"                   | null            | null
    "1"                   | "-1"                  | null            | null
    "0"                   | "1"                   | null            | null
    "1"                   | "0"                   | DDId.ONE        | DDId.ZERO
    "$TRACE_ID_MAX"       | "1"                   | DDId.MAX        | DDId.ONE
    "${TRACE_ID_MAX + 1}" | "1"                   | null            | null
    "1"                   | "$TRACE_ID_MAX"       | DDId.ONE        | DDId.MAX
    "1"                   | "${TRACE_ID_MAX + 1}" | null            | null
  }

  def "extract http headers with end to end"() {
    setup:
    def headers = [
      ""                                      : "empty key",
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "t0"): endToEndStartTime,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info",
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDId.from(traceId)
    context.spanId == DDId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.endToEndStartTime == endToEndStartTime * 1000000L

    where:
    traceId | spanId | endToEndStartTime
    "1"     | "2"    | 0
    "2"     | "3"    | 1610001234
  }

  def "extract common http headers"() {
    setup:
    def headers = [
      (HttpCodec.USER_AGENT_KEY): 'some-user-agent',
      (HttpCodec.X_CLUSTER_CLIENT_IP_KEY): '1.1.1.1',
      (HttpCodec.X_REAL_IP_KEY): '2.2.2.2',
      (HttpCodec.CLIENT_IP_KEY): '3.3.3.3',
      (HttpCodec.TRUE_CLIENT_IP_KEY): '4.4.4.4',
      (HttpCodec.VIA_KEY): '5.5.5.5',
      (HttpCodec.FORWARDED_FOR_KEY): '6.6.6.6',
      (HttpCodec.X_FORWARDED_KEY): '7.7.7.7'
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    assert context.userAgent == 'some-user-agent'
    assert context.XClusterClientIp == '1.1.1.1'
    assert context.XRealIp == '2.2.2.2'
    assert context.clientIp == '3.3.3.3'
    assert context.trueClientIp == '4.4.4.4'
    assert context.via == '5.5.5.5'
    assert context.forwardedFor == '6.6.6.6'
    assert context.XForwarded == '7.7.7.7'
  }
}

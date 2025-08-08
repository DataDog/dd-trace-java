package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DD128bTraceId
import datadog.trace.api.DD64bTraceId
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.internal.util.LongStringUtils
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

class DatadogHttpExtractorTest extends DDSpecification {

  private DynamicConfig dynamicConfig
  private HttpCodec.Extractor _extractor

  private HttpCodec.Extractor getExtractor() {
    _extractor ?: (_extractor = createExtractor(Config.get()))
  }

  private HttpCodec.Extractor createExtractor(Config config) {
    DatadogHttpCodec.newExtractor(config, { dynamicConfig.captureTraceConfig() })
  }

  boolean origAppSecActive

  void setup() {
    dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping(["SOME_CUSTOM_BAGGAGE_HEADER": "some-baggage", "SOME_CUSTOM_BAGGAGE_HEADER_2": "some-CaseSensitive-baggage"])
      .apply()
    origAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true

    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true")
  }

  void cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = origAppSecActive
    extractor.cleanup()
  }

  def "extract http headers"() {
    setup:
    injectEnvConfig("DD_TRACE_REQUEST_HEADER_TAGS_COMMA_ALLOWED", "$allowComma")
    def extractor = createExtractor(Config.get())
    def headers = [
      ""                                      : "empty key",
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info,and-more",
      SOME_CUSTOM_BAGGAGE_HEADER              : "my-interesting-baggage-info",
      SOME_CUSTOM_BAGGAGE_HEADER_2            : "my-interesting-baggage-info-2",
    ]
    def expectedTagValue = allowComma ? "my-interesting-info,and-more" : "my-interesting-info"

    if (samplingPriority != PrioritySampling.UNSET) {
      headers.put(SAMPLING_PRIORITY_KEY, "$samplingPriority".toString())
    }

    if (origin) {
      headers.put(ORIGIN_KEY, origin)
    }

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDTraceId.from(traceId)
    context.spanId == DDSpanId.from(spanId)
    context.baggage == ["k1"                        : "v1",
      "k2"                        : "v2",
      "some-baggage"              : "my-interesting-baggage-info",
      "some-CaseSensitive-baggage": "my-interesting-baggage-info-2"]
    context.tags == ["some-tag": "$expectedTagValue"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    cleanup:
    extractor.cleanup()

    where:
    traceId               | spanId                | samplingPriority              | origin   | allowComma
    "1"                   | "2"                   | PrioritySampling.UNSET        | null     | true
    "2"                   | "3"                   | PrioritySampling.SAMPLER_KEEP | "saipan" | false
    "$TRACE_ID_MAX"       | "${TRACE_ID_MAX - 1}" | PrioritySampling.UNSET        | "saipan" | true
    "${TRACE_ID_MAX - 1}" | "$TRACE_ID_MAX"       | PrioritySampling.SAMPLER_KEEP | "saipan" | false
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

  def "extract http headers with 128-bit trace ID"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceId.toString(),
      (SPAN_ID_KEY.toUpperCase())             : "2",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_HEADER                             : "my-interesting-info"
    ] + additionalHeader

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == expectedTraceId
    context.spanId == DDSpanId.from("2")
    context.baggage == ["k1": "v1",
      "k2": "v2"]
    context.tags == ["some-tag": "my-interesting-info"]

    where:
    hexId << [
      "1",
      "123456789abcdef0",
      "123456789abcdef0123456789abcdef0",
      "64184f2400000000123456789abcdef0",
      "f" * 32
    ]
    traceId = DD128bTraceId.fromHex(hexId)
    is128bTrace = traceId.toHighOrderLong() != 0
    expectedTraceId = is128bTrace ? traceId : DD64bTraceId.from(traceId.toLong())
    additionalHeader = is128bTrace ? [(DATADOG_TAGS_KEY.toUpperCase()) : '_dd.p.tid=' + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16)] : [:]
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
    traceId               | spanId                | expectedTraceId  | expectedSpanId
    "-1"                  | "1"                   | null             | null
    "1"                   | "-1"                  | null             | null
    "0"                   | "1"                   | null             | null
    "1"                   | "0"                   | DD64bTraceId.ONE | DDSpanId.ZERO
    "$TRACE_ID_MAX"       | "1"                   | DD64bTraceId.MAX | 1
    "${TRACE_ID_MAX + 1}" | "1"                   | null             | null
    "1"                   | "$TRACE_ID_MAX"       | DD64bTraceId.ONE | DDSpanId.MAX
    "1"                   | "${TRACE_ID_MAX + 1}" | null             | null
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
      SOME_CUSTOM_BAGGAGE_HEADER              : "my-interesting-baggage-info",
      SOME_CUSTOM_BAGGAGE_HEADER_2            : "my-interesting-baggage-info-2",
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDTraceId.from(traceId)
    context.spanId == DDSpanId.from(spanId)
    context.baggage == ["k1": "v1",
      "k2": "v2",
      "some-baggage": "my-interesting-baggage-info",
      "some-CaseSensitive-baggage": "my-interesting-baggage-info-2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.endToEndStartTime == endToEndStartTime * 1000000L

    where:
    traceId | spanId | endToEndStartTime
    "1"     | "2"    | 0
    "2"     | "3"    | 1610001234
  }

  def "baggage is mapped on context creation"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
      SOME_CUSTOM_BAGGAGE_HEADER              : "mappedBaggageValue",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "v2",
      SOME_ARBITRARY_HEADER                             : "my-interesting-info",
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (ctxCreated) {
      assert context != null
      assert context.getBaggage() == [
        "some-baggage"     : "mappedBaggageValue",
        "k1"               : "v1",
        "k2"               : "v2",
      ]
    } else {
      assert context == null
    }

    where:
    ctxCreated     | traceId                                | spanId
    false          | "-1"                                   | "1"
    false          | "1"                                    | "-1"
    true           | "0"                                    | "1"
    true           | "${TRACE_ID_MAX - 1}" | "${TRACE_ID_MAX - 1}"
  }

  def "extract common http headers"() {
    setup:
    def headers = [
      (HttpCodec.USER_AGENT_KEY): 'some-user-agent',
      (HttpCodec.X_CLUSTER_CLIENT_IP_KEY): '1.1.1.1',
      (HttpCodec.X_REAL_IP_KEY): '2.2.2.2',
      (HttpCodec.X_CLIENT_IP_KEY): '3.3.3.3',
      (HttpCodec.TRUE_CLIENT_IP_KEY): '4.4.4.4',
      (HttpCodec.FORWARDED_FOR_KEY): '5.5.5.5',
      (HttpCodec.FORWARDED_KEY): '6.6.6.6',
      (HttpCodec.FASTLY_CLIENT_IP_KEY): '7.7.7.7',
      (HttpCodec.CF_CONNECTING_IP_KEY): '8.8.8.8',
      (HttpCodec.CF_CONNECTING_IP_V6_KEY): '9.9.9.9',
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    assert context.userAgent == 'some-user-agent'
    assert context.XClusterClientIp == '1.1.1.1'
    assert context.XRealIp == '2.2.2.2'
    assert context.XClientIp == '3.3.3.3'
    assert context.trueClientIp == '4.4.4.4'
    assert context.forwardedFor == '5.5.5.5'
    assert context.forwarded == '6.6.6.6'
    assert context.fastlyClientIp == '7.7.7.7'
    assert context.cfConnectingIp == '8.8.8.8'
    assert context.cfConnectingIpv6 == '9.9.9.9'
  }
}

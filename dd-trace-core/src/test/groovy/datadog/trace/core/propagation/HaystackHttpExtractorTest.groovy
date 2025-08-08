package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DD64bTraceId
import datadog.trace.api.DynamicConfig

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.test.util.DDSpecification

class HaystackHttpExtractorTest extends DDSpecification {

  DynamicConfig dynamicConfig
  HttpCodec.Extractor extractor

  boolean origAppSecActive

  void setup() {
    DynamicConfig dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping(["SOME_CUSTOM_BAGGAGE_HEADER": "some-baggage", "SOME_CUSTOM_BAGGAGE_HEADER_2": "some-CaseSensitive-baggage"])
      .apply()
    extractor = HaystackHttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() })
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
      (TRACE_ID_KEY.toUpperCase())            : traceUuid,
      (SPAN_ID_KEY.toUpperCase())             : spanUuid,
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k1"): "v1",
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k2"): "%76%32",             // v2 encoded once
      (OT_BAGGAGE_PREFIX.toUpperCase() + "k3"): "%25%37%36%25%33%33", // v3 encoded twice
      SOME_HEADER                             : "my-interesting-info",
      SOME_CUSTOM_BAGGAGE_HEADER              : "my-interesting-baggage-info",
      SOME_CUSTOM_BAGGAGE_HEADER_2            : "my-interesting-baggage-info-2",
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDTraceId.from(traceId)
    context.spanId == DDSpanId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2",
      "k3": "%76%33", // expect value decoded only once
      "Haystack-Trace-ID": traceUuid, "Haystack-Span-ID": spanUuid,
      "some-baggage": "my-interesting-baggage-info",
      "some-CaseSensitive-baggage": "my-interesting-baggage-info-2"]
    context.tags == ["some-tag": "my-interesting-info"]
    context.samplingPriority == samplingPriority
    context.origin == origin

    where:
    traceId               | spanId                | samplingPriority              | origin | traceUuid                              | spanUuid
    "1"                   | "2"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000001" | "44617461-646f-6721-0000-000000000002"
    "2"                   | "3"                   | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-0000-000000000002" | "44617461-646f-6721-0000-000000000003"
    "${TRACE_ID_MAX}"     | "${TRACE_ID_MAX - 6}" | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-ffffffffffff" | "44617461-646f-6721-ffff-fffffffffff9"
    "${TRACE_ID_MAX - 1}" | "${TRACE_ID_MAX - 7}" | PrioritySampling.SAMPLER_KEEP | null   | "44617461-646f-6721-ffff-fffffffffffe" | "44617461-646f-6721-ffff-fffffffffff8"
  }

  def "extract header tags with no propagation"() {
    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]


    where:
    headers                              | _
    [SOME_HEADER: "my-interesting-info"] | _
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
    context.spanId == 2
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "123"
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
    context.spanId == 2
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "123"
    tagOnlyCtx = [
      "X-Forwarded-For" : forwardedIp,
      "X-Forwarded-Port": forwardedPort
    ]
    fullCtx = [
      (TRACE_ID_KEY.toUpperCase()): 1,
      (SPAN_ID_KEY.toUpperCase()) : 2,
      "x-forwarded-for"           : forwardedIp,
      "x-forwarded-port"          : forwardedPort
    ]
  }

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap()) == null
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
        "Haystack-Trace-ID": traceId,
        "Haystack-Span-ID" : spanId,
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
    true           | "44617461-646f-6721-463a-c35c9f6413ad" | "44617461-646f-6721-463a-c35c9f6413ad"
  }

  def "extract 128 bit id truncates id to 64 bit"() {
    setup:
    def headers = [
      (TRACE_ID_KEY.toUpperCase())            : traceId,
      (SPAN_ID_KEY.toUpperCase())             : spanId,
    ]

    when:
    final TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.spanId == expectedSpanId
    }
    if (ctxCreated) {
      assert context != null
    } else {
      assert context == null
    }

    where:
    ctxCreated | traceId                                | spanId                                 | expectedTraceId                     | expectedSpanId
    false      | "-1"                                   | "1"                                    | null                                | DDSpanId.ZERO
    false      | "1"                                    | "-1"                                   | null                                | DDSpanId.ZERO
    true       | "0"                                    | "1"                                    | null                                | DDSpanId.ZERO
    true       | "00001"                                | "00001"                                | DDTraceId.ONE                       | 1
    true       | "463ac35c9f6413ad"                     | "463ac35c9f6413ad"                     | DDTraceId.from(5060571933882717101) | 5060571933882717101
    true       | "463ac35c9f6413ad48485a3953bb6124"     | "1"                                    | DDTraceId.from(5208512171318403364) | 1
    true       | "44617461-646f-6721-463a-c35c9f6413ad" | "44617461-646f-6721-463a-c35c9f6413ad" | DDTraceId.from(5060571933882717101) | 5060571933882717101
    true       | "f" * 16                               | "1"                                    | DD64bTraceId.MAX                    | 1
    true       | "a" * 16 + "f" * 16                    | "1"                                    | DD64bTraceId.MAX                    | 1
    false      | "1" + "f" * 32                         | "1"                                    | null                                | 1
    false      | "0" + "f" * 32                         | "1"                                    | null                                | 1
    true       | "1"                                    | "f" * 16                               | DDTraceId.ONE                       | DDSpanId.MAX
    false      | "1"                                    | "1" + "f" * 16                         | null                                | DDSpanId.ZERO
    true       | "1"                                    | "000" + "f" * 16                       | DDTraceId.ONE                       | DDSpanId.MAX
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
    assert context.forwarded  == '6.6.6.6'
    assert context.fastlyClientIp == '7.7.7.7'
    assert context.cfConnectingIp == '8.8.8.8'
    assert context.cfConnectingIpv6 == '9.9.9.9'
  }
}

package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.DD64bTraceId
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.core.scopemanager.ScopeContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted.SPAN_CONTEXT

class XRayHttpExtractorTest extends DDSpecification {

  DynamicConfig dynamicConfig
  HttpCodec.Extractor extractor
  HttpCodec.ScopeContextBuilder builder

  boolean origAppSecActive

  void setup() {
    dynamicConfig = DynamicConfig.create()
      .setHeaderTags(["SOME_HEADER": "some-tag"])
      .setBaggageMapping(["SOME_CUSTOM_BAGGAGE_HEADER": "some-baggage", "SOME_CUSTOM_BAGGAGE_HEADER_2": "some-CaseSensitive-baggage"])
      .apply()
    extractor = XRayHttpCodec.newExtractor(Config.get(), { dynamicConfig.captureTraceConfig() })
    builder = new HttpCodec.ScopeContextAppender()
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
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')};" +
      "Parent=${spanId.padLeft(16, '0')}${samplingPriority};=empty key;empty value=;=;;",
      SOME_HEADER : "my-interesting-info",
      SOME_CUSTOM_BAGGAGE_HEADER : "my-interesting-baggage-info",
      SOME_CUSTOM_BAGGAGE_HEADER_2 : "my-interesting-baggage-info-2",
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    final ExtractedContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context.traceId == DDTraceId.fromHex("$traceId")
    context.spanId == DDSpanId.fromHex("$spanId")
    context.baggage == [
      "empty value" : "",
      "some-baggage": "my-interesting-baggage-info",
      "some-CaseSensitive-baggage": "my-interesting-baggage-info-2"
    ]
    context.tags == [
      "some-tag"    : "my-interesting-info"
    ]
    context.samplingPriority == expectedSamplingPriority
    context.origin == null

    where:
    traceId        | spanId           | samplingPriority | expectedSamplingPriority
    "1"            | "2"              | ""               | PrioritySampling.UNSET
    "2"            | "3"              | ";Sampled=1"     | PrioritySampling.SAMPLER_KEEP
    "3"            | "4"              | ";Sampled=0"     | PrioritySampling.SAMPLER_DROP
    "f" * 16       | "f" * 15 + "e"   | ";Sampled=0"     | PrioritySampling.SAMPLER_DROP
    "f" * 15 + "e" | "f" * 16         | ";Sampled=1"     | PrioritySampling.SAMPLER_KEEP
  }

  def "extract header tags with no propagation"() {
    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    !(context instanceof ExtractedContext)
    context.getTags() == ["some-tag": "my-interesting-info"]

    where:
    headers                              | _
    [SOME_HEADER: "my-interesting-info"] | _
  }

  def "extract headers with forwarding"() {
    when:
    extractor.extract(builder, tagOnlyCtx, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context != null
    !(context instanceof ExtractedContext)
    context.forwarded == "for=$forwardedIp:$forwardedPort"

    when:
    extractor.extract(builder, fullCtx, ContextVisitors.stringValuesMap())
    context = builder.build().get(SPAN_CONTEXT)

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
      "x-amzn-trace-id"  : "Root=1-00000000-000000000000000000000001;Parent=0000000000000002",
      "Forwarded" : "for=$forwardedIp:$forwardedPort"
    ]
  }

  def "extract headers with x-forwarding"() {
    when:
    extractor.extract(builder, tagOnlyCtx, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context != null
    context instanceof TagContext
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    when:
    extractor.extract(builder, fullCtx, ContextVisitors.stringValuesMap())
    context = builder.build().get(SPAN_CONTEXT)

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.XForwardedFor == forwardedIp
    context.XForwardedPort == forwardedPort

    where:
    forwardedIp = "1.2.3.4"
    forwardedPort = "1234"
    tagOnlyCtx = [
      "X-Forwarded-For" : forwardedIp,
      "X-Forwarded-Port": forwardedPort
    ]
    fullCtx = [
      "x-amzn-trace-id"  : "Root=1-00000000-000000000000000000000001;Parent=0000000000000002",
      "x-forwarded-for"  : forwardedIp,
      "x-forwarded-port" : forwardedPort
    ]
  }

  def "no context with empty headers"() {
    when:
    extractor.extract(builder, ["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap())

    then:
    builder.build() == ScopeContext.empty()
  }

  def "no context with invalid non-numeric ID"() {
    setup:
    def headers = [
      "x-amzn-trace-Id"  : "Root=1-00000000-00000000000000000traceId;Parent=0000000000spanId",
      SOME_HEADER        : "my-interesting-info",
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context == null
  }

  def "no context with too large trace-id"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8"
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context == null
  }

  def "extract http headers with non-zero epoch"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-5759e988-00000000e1be46a994272793;Parent=53995c3f42cd8ad8"
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context.traceId == DDTraceId.fromHex("e1be46a994272793")
    context.spanId == DDSpanId.fromHex("53995c3f42cd8ad8")
    context.origin == null
  }

  def "extract ids while retaining the original string"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')};Parent=${spanId.padLeft(16, '0')}"
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    final ExtractedContext context = builder.build().get(SPAN_CONTEXT)

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.traceId.toHexStringPadded(16) == traceId.padLeft(16, '0')
      assert context.spanId == expectedSpanId
      assert DDSpanId.toHexStringPadded(context.spanId) == spanId.padLeft(16, '0')
    } else {
      assert context == null
    }

    where:
    traceId            | spanId             | expectedTraceId                       | expectedSpanId
    "00001"            | "00001"            | DD64bTraceId.ONE | 1
    "463ac35c9f6413ad" | "463ac35c9f6413ad" | DD64bTraceId.fromHex("463ac35c9f6413ad") | DDSpanId.from("5060571933882717101")
    "48485a3953bb6124" | "1"                | DD64bTraceId.fromHex("48485a3953bb6124") | 1
    "f" * 16           | "1"                | DD64bTraceId.MAX                         | 1
    "1"                | "f" * 16           | DD64bTraceId.ONE                         | DDSpanId.MAX
  }

  def "extract headers with end-to-end"() {
    setup:
    def ctx = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')}" +
      ";Parent=${spanId.padLeft(16, '0')};k1=v1;t0=${endToEndStartTime};k2=v2"
    ]

    when:
    extractor.extract(builder, ctx, ContextVisitors.stringValuesMap())
    ExtractedContext context = builder.build().get(SPAN_CONTEXT)

    then:
    context.traceId == DDTraceId.from(traceId)
    context.spanId == DDSpanId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2"]
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
      (HttpCodec.X_CLIENT_IP_KEY): '3.3.3.3',
      (HttpCodec.TRUE_CLIENT_IP_KEY): '4.4.4.4',
      (HttpCodec.FORWARDED_FOR_KEY): '5.5.5.5',
      (HttpCodec.X_FORWARDED_KEY): '6.6.6.6',
      (HttpCodec.FASTLY_CLIENT_IP_KEY): '7.7.7.7',
      (HttpCodec.CF_CONNECTING_IP_KEY): '8.8.8.8',
      (HttpCodec.CF_CONNECTING_IP_V6_KEY): '9.9.9.9',
    ]

    when:
    extractor.extract(builder, headers, ContextVisitors.stringValuesMap())
    final TagContext context = builder.build().get(SPAN_CONTEXT)

    then:
    assert context.userAgent == 'some-user-agent'
    assert context.XClusterClientIp == '1.1.1.1'
    assert context.XRealIp == '2.2.2.2'
    assert context.XClientIp == '3.3.3.3'
    assert context.trueClientIp == '4.4.4.4'
    assert context.forwardedFor == '5.5.5.5'
    assert context.XForwarded == '6.6.6.6'
    assert context.fastlyClientIp == '7.7.7.7'
    assert context.cfConnectingIp == '8.8.8.8'
    assert context.cfConnectingIpv6 == '9.9.9.9'
  }
}

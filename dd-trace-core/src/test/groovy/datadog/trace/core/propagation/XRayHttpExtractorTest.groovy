package datadog.trace.core.propagation

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED

class XRayHttpExtractorTest extends DDSpecification {

  HttpCodec.Extractor extractor = XRayHttpCodec.newExtractor(["SOME_HEADER": "some-tag"])

  def setup() {
    injectSysConfig(PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED, "true")
  }

  def "extract http headers"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')};" +
      "Parent=${spanId.padLeft(16, '0')}${samplingPriority};=empty key;empty value=;=;;",
      SOME_HEADER : "my-interesting-info"
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDId.fromHex("$traceId")
    context.spanId == DDId.fromHex("$spanId")
    context.baggage == [
      "empty value" : ""
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
    TagContext context = extractor.extract(tagOnlyCtx, ContextVisitors.stringValuesMap())

    then:
    context != null
    context instanceof TagContext
    context.forwardedIp == forwardedIp
    context.forwardedPort == forwardedPort

    when:
    context = extractor.extract(fullCtx, ContextVisitors.stringValuesMap())

    then:
    context instanceof ExtractedContext
    context.traceId.toLong() == 1
    context.spanId.toLong() == 2
    context.forwardedIp == forwardedIp
    context.forwardedPort == forwardedPort

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

  def "extract empty headers returns null"() {
    expect:
    extractor.extract(["ignored-header": "ignored-value"], ContextVisitors.stringValuesMap()) == null
  }

  def "extract http headers with invalid non-numeric ID"() {
    setup:
    def headers = [
      "x-amzn-trace-Id"  : "Root=1-00000000-00000000000000000traceId;Parent=0000000000spanId",
      SOME_HEADER        : "my-interesting-info",
    ]

    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null
  }

  def "extract http headers with non-Datadog X-Amzn-Trace-Id value"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1"
    ]


    when:
    TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    context == null
  }

  def "extract ids while retaining the original string"() {
    setup:
    def headers = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')};Parent=${spanId.padLeft(16, '0')}"
    ]

    when:
    final ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap())

    then:
    if (expectedTraceId) {
      assert context.traceId == expectedTraceId
      assert context.traceId.toHexStringOrOriginal() == traceId.padLeft(16, '0')
      assert context.spanId == expectedSpanId
      assert context.spanId.toHexStringOrOriginal() == spanId.padLeft(16, '0')
    } else {
      assert context == null
    }

    where:
    traceId            | spanId             | expectedTraceId                  | expectedSpanId
    "00001"            | "00001"            | DDId.ONE                         | DDId.ONE
    "463ac35c9f6413ad" | "463ac35c9f6413ad" | DDId.from("5060571933882717101") | DDId.from("5060571933882717101")
    "48485a3953bb6124" | "1"                | DDId.from("5208512171318403364") | DDId.ONE
    "f" * 16           | "1"                | DDId.MAX                         | DDId.ONE
    "1"                | "f" * 16           | DDId.ONE                         | DDId.MAX
  }

  def "extract headers with end-to-end"() {
    setup:
    def ctx = [
      'X-Amzn-Trace-Id' : "Root=1-00000000-00000000${traceId.padLeft(16, '0')}" +
      ";Parent=${spanId.padLeft(16, '0')};k1=v1;t0=${endToEndStartTime};k2=v2"
    ]

    when:
    ExtractedContext context = extractor.extract(ctx, ContextVisitors.stringValuesMap())

    then:
    context.traceId == DDId.from(traceId)
    context.spanId == DDId.from(spanId)
    context.baggage == ["k1": "v1", "k2": "v2"]
    context.endToEndStartTime == endToEndStartTime * 1000000L

    where:
    traceId | spanId | endToEndStartTime
    "1"     | "2"    | 0
    "2"     | "3"    | 1610001234
  }
}

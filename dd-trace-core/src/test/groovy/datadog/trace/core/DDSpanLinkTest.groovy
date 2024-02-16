package datadog.trace.core

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.DynamicConfig
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import datadog.trace.bootstrap.instrumentation.api.SpanLinkAttributes
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.W3CHttpCodec
import datadog.trace.core.test.DDCoreSpecification
import groovy.json.JsonSlurper
import spock.lang.Shared

import java.util.stream.IntStream

import static datadog.trace.api.DDTags.SPAN_LINKS
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.SAMPLED_FLAG
import static datadog.trace.bootstrap.instrumentation.api.SpanLinkAttributes.EMPTY
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY
import static java.util.stream.Collectors.toList

class DDSpanLinkTest extends DDCoreSpecification {
  @Shared def writer = new ListWriter()
  @Shared def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "create span link from extracted context"() {
    setup:
    def traceId = "11223344556677889900aabbccddeeff"
    def spanId = "123456789abcdef0"
    def traceState = "dd=s:$sample;o:some;t.dm:-4"
    Map<String, String> headers = [
      (TRACE_PARENT_KEY.toUpperCase()): "00-$traceId-$spanId-$traceFlags",
      (TRACE_STATE_KEY.toUpperCase()) : "$traceState"
    ]
    def extractor = W3CHttpCodec.newExtractor(Config.get(), { DynamicConfig.create().apply().captureTraceConfig() })

    when:
    ExtractedContext context = extractor.extract(headers, ContextVisitors.stringValuesMap()) as ExtractedContext
    def link = DDSpanLink.from(context)

    then:
    link.traceId() == DDTraceId.fromHex(traceId)
    link.spanId() == DDSpanId.fromHex(spanId)
    link.traceFlags() == (sampled ? SAMPLED_FLAG : DEFAULT_FLAGS)
    link.traceState() == "$traceState;t.tid:${traceId.substring(0, 16)}"

    where:
    sampled << [true, false]
    traceFlags = sampled ? '01' : '00'
    sample = sampled ? '1' : '-1'
  }

  def "test span link encoding - tag max size"() {
    setup:
    def tooManyLinkCount = 300
    def builder = tracer.buildSpan("test", "operation")
    def links = IntStream.range(0, tooManyLinkCount).mapToObj {createLink(it)}.collect(toList())
    def slurper = new JsonSlurper()

    when:
    for (def link : links) {
      builder.withLink(link)
    }
    def span = builder.start()
    span.finish()
    // Wait for flush and get the first trace / first span links from tags
    writer.waitForTraces(1)
    def spanLinksTag = writer[0][0].tags[SPAN_LINKS] as String
    // Parse span links JSON data
    def decodedSpanLinks = slurper.parseText(spanLinksTag) as List

    then:
    spanLinksTag.length() < DDSpanLink.TAG_MAX_LENGTH
    decodedSpanLinks.size() < tooManyLinkCount
    spanLinksTag.length() / decodedSpanLinks.size() * (decodedSpanLinks.size() + 1) > DDSpanLink.TAG_MAX_LENGTH
    for (i in 0..<decodedSpanLinks.size()) {
      assertLink(links[i], decodedSpanLinks[i] as DDSpanLink.SpanLinkJson)
    }
  }

  def "test span links encoding - omitted empty keys"() {
    setup:
    def builder = tracer.buildSpan("test", "operation")
    def link = new SpanLink(
      DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
      DDSpanId.fromHex("123456789abcdef0"),
      DEFAULT_FLAGS,
      "",
      EMPTY
      )

    when:
    def span = builder.withLink(link).start()
    span.finish()
    // Wait for flush and get the first trace / first span links from tags
    writer.waitForTraces(1)
    def spanLinksTag = writer[0][0].tags[SPAN_LINKS] as String

    then:
    spanLinksTag == '[{"span_id":"123456789abcdef0","trace_id":"11223344556677889900aabbccddeeff"}]'
  }

  def "add span link at any time"() {
    setup:
    def builder = tracer.buildSpan("test", "operation")
    def links = []
    def slurper = new JsonSlurper()

    when:
    if (beforeStart) {
      def link = createLink(0)
      builder.withLink(link)
      links += link
    }
    def span = builder.start()
    if (afterStart) {
      def link = createLink(1)
      span.addLink(link)
      links += link
    }
    span.finish()
    // Wait for flush and get the first trace / first span links from tags
    writer.waitForTraces(1)
    def spanLinksTag = writer[0][0].tags[SPAN_LINKS] as String
    // Parse span links JSON data
    def decodedSpanLinks = spanLinksTag == null ? [] : slurper.parseText(spanLinksTag) as List

    then:
    decodedSpanLinks.size() == (beforeStart ? 1 : 0) + (afterStart ? 1 : 0)
    for (i in 0..<decodedSpanLinks.size()) {
      assertLink(links[i], decodedSpanLinks[i] as DDSpanLink.SpanLinkJson)
    }

    where:
    beforeStart | afterStart
    false       | false
    true        | false
    false       | true
    true        | true
  }

  def "filter null links"() {
    setup:
    def builder = tracer.buildSpan("test", "operation")

    when:
    def span = builder.withLink(null).start()
    span.addLink(null)
    span.finish()
    // Wait for flush and get the first trace / first span links from tags
    writer.waitForTraces(1)
    def spanLinksTag = writer[0][0].tags[SPAN_LINKS] as String

    then:
    spanLinksTag == null
  }

  def createLink(int index) {
    def attributes = ['link-index': Integer.toString(index)]

    return new SpanLink(
      DDTraceId.fromHex(String.format("11223344556677889900aabbccdd%04d", index)),
      DDSpanId.fromHex(String.format("123456789abc%04d", index)),
      index % 2 == 0 ? SAMPLED_FLAG : DEFAULT_FLAGS,
      "",
      SpanLinkAttributes.fromMap(attributes))
  }

  def assertLink(SpanLink expected, DDSpanLink.SpanLinkJson actual) {
    assert expected.traceId().toHexString() == actual.trace_id
    assert DDSpanId.toHexString(expected.spanId()) == actual.span_id
    if (expected.traceFlags() == DEFAULT_FLAGS) {
      assert null == actual.flags
    } else {
      assert expected.traceFlags() == actual.flags
    }
    if (expected.traceState().isEmpty()) {
      assert null == actual.tracestate
    } else {
      assert expected.traceState() == actual.trace_id
    }
    if (expected.attributes().isEmpty()) {
      assert null == actual.attributes
    } else {
      assert expected.attributes().asMap() == actual.attributes
    }
  }
}

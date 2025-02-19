package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.api.DDTraceId
import spock.lang.Specification

class ExtractedSpanTest extends Specification {
  def 'test extracted span from partial tracing context'() {
    given:
    def tags = ['tag-1': 'value-1', 'tag-2': 'value-2']
    def baggage = ['baggage-1': 'value-1', 'baggage-2': 'value-2']
    def traceId = DDTraceId.from(12345)
    def context = new TagContext('origin', tags, null, baggage, 0, null, null, traceId)
    def extractedSpan = new ExtractedSpan(context)

    expect:
    extractedSpan.getTraceId() == traceId
    extractedSpan.getSpanId() == context.getSpanId()
    extractedSpan.context() == context
    extractedSpan.getTags() == tags
    extractedSpan.getTag('tag-1') == 'value-1'
    extractedSpan.getBaggageItem('baggage-2') == 'value-2'
    extractedSpan.isSameTrace(new ExtractedSpan(context))
    extractedSpan.toString() != null

    when:
    extractedSpan.setTag('tag-1', 'updated')
    extractedSpan.setBaggageItem('baggage-2', 'updated')

    then:
    extractedSpan.getTag('tag-1') == 'value-1'
    extractedSpan.getBaggageItem('baggage-2') == 'value-2'
  }

  def 'test extracted span from custom span context'() {
    given:
    def context = Mock(AgentSpanContext)
    context.getTraceId() >> DDTraceId.from(12345)
    context.getSpanId() >> 67890
    context.baggageItems() >> Collections.<String, String>emptyMap().entrySet()
    def extractedSpan = new ExtractedSpan(context)

    expect:
    extractedSpan.getTraceId() == context.getTraceId()
    extractedSpan.getSpanId() == context.getSpanId()
    extractedSpan.context() == context
    extractedSpan.getTags().isEmpty()
    extractedSpan.getTag('tag-1') == null
    extractedSpan.getBaggageItem('baggage-2') == null
    extractedSpan.isSameTrace(new ExtractedSpan(context))
    extractedSpan.toString() != null
  }
}

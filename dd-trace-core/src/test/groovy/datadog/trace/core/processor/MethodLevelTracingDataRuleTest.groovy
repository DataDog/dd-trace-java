package datadog.trace.core.processor

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.core.SpanFactory
import datadog.trace.core.interceptor.TraceHeuristicsEvaluator
import datadog.trace.util.test.DDSpecification

class MethodLevelTracingDataRuleTest extends DDSpecification {
  def traceHeuristicsEvaluator = Mock(TraceHeuristicsEvaluator)

  def processor = new TraceProcessor(traceHeuristicsEvaluator)

  def span = SpanFactory.newSpanOf(0)
  def trace = [span]

  def "mlt tag is always removed"() {
    when:
    span.setTag(InstrumentationTags.DD_MLT, value)
    processor.onTraceComplete(trace)

    then:
    span.getTags().get(InstrumentationTags.DD_MLT) == null

    where:
    value << [null, new byte[0], "ignored"]
  }

  def "mlt data is split into at 5000 characters"() {
    given:
    byte[] data = new byte[10000]
    new Random().nextBytes(data)

    when:
    span.setTag(InstrumentationTags.DD_MLT, data)
    processor.onTraceComplete(trace)

    then:
    span.getTags().get(InstrumentationTags.DD_MLT + ".0").size() == 5000
    span.getTags().get(InstrumentationTags.DD_MLT + ".1").size() == 5000
    span.getTags().get(InstrumentationTags.DD_MLT + ".2").size() == 3336
    span.getTags().get(InstrumentationTags.DD_MLT + ".3") == null
  }
}

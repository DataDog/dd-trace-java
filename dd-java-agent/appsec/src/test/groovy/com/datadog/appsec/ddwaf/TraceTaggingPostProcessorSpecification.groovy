package com.datadog.appsec.ddwaf

import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.AppSecEvent
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification

class TraceTaggingPostProcessorSpecification extends DDSpecification {

  def 'should serialize trace attributes to trace segment'() {
    setup:
    TraceSegmentPostProcessor processor = new TraceTaggingPostProcessor()
    TraceSegment segment = Mock()
    AppSecRequestContext ctx = Mock()
    Collection<AppSecEvent> events = []

    Map<String, Object> traceAttributes = [
      'appsec.rule.id': 'test-rule-123',
      'appsec.attack.type': 'sql_injection',
      'appsec.confidence': 0.95
    ]

    when:
    processor.processTraceSegment(segment, ctx, events)

    then:
    1 * ctx.getTraceAttributes() >> traceAttributes
    1 * segment.setTagTop('appsec.rule.id', 'test-rule-123')
    1 * segment.setTagTop('appsec.attack.type', 'sql_injection')
    1 * segment.setTagTop('appsec.confidence', 0.95)
    0 * segment._(*_)
  }

  def 'should handle null trace attributes'() {
    setup:
    TraceSegmentPostProcessor processor = new TraceTaggingPostProcessor()
    TraceSegment segment = Mock()
    AppSecRequestContext ctx = Mock()
    Collection<AppSecEvent> events = []

    when:
    processor.processTraceSegment(segment, ctx, events)

    then:
    1 * ctx.getTraceAttributes() >> null
    0 * segment._(*_)
  }

  def 'should handle empty trace attributes'() {
    setup:
    TraceSegmentPostProcessor processor = new TraceTaggingPostProcessor()
    TraceSegment segment = Mock()
    AppSecRequestContext ctx = Mock()
    Collection<AppSecEvent> events = []

    when:
    processor.processTraceSegment(segment, ctx, events)

    then:
    1 * ctx.getTraceAttributes() >> [:]
    0 * segment._(*_)
  }

  def 'should handle null keys or values in trace attributes'() {
    setup:
    TraceSegmentPostProcessor processor = new TraceTaggingPostProcessor()
    TraceSegment segment = Mock()
    AppSecRequestContext ctx = Mock()
    Collection<AppSecEvent> events = []

    // Create a Map with actual null keys using put() method
    Map<String, Object> traceAttributes = new HashMap<>()
    traceAttributes.put('valid.key', 'valid.value')
    traceAttributes.put(null, 'null.key')  // Actual null key
    traceAttributes.put('valid.key2', null)  // Null value
    traceAttributes.put('', 'empty.key')  // Empty key

    when:
    processor.processTraceSegment(segment, ctx, events)

    then:
    1 * ctx.getTraceAttributes() >> traceAttributes
    1 * segment.setTagTop('valid.key', 'valid.value')
    0 * segment._(*_)
  }

  def 'should handle exceptions during serialization'() {
    setup:
    TraceSegmentPostProcessor processor = new TraceTaggingPostProcessor()
    TraceSegment segment = Mock()
    AppSecRequestContext ctx = Mock()
    Collection<AppSecEvent> events = []

    Map<String, Object> traceAttributes = [
      'good.key': 'good.value',
      'bad.key': 'bad.value'
    ]

    when:
    processor.processTraceSegment(segment, ctx, events)

    then:
    1 * ctx.getTraceAttributes() >> traceAttributes
    1 * segment.setTagTop('good.key', 'good.value')
    1 * segment.setTagTop('bad.key', 'bad.value') >> { throw new RuntimeException('Test exception') }
    0 * segment._(*_)
  }
}

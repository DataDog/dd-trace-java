package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.PrioritizationStrategy
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE

class PrioritizationWithSpanProcessingWorkerTest extends DDSpecification {

  def "ensure trace strategy tries to send kept and unset priority traces to the primary queue until offer(..) is true, dropped traces to the span sampling queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    SpanProcessingWorker spanProcessingWorker = Mock(SpanProcessingWorker)
    PrioritizationStrategy blocking = ENSURE_TRACE.create(primary, secondary, { false }, spanProcessingWorker)

    when:
    blocking.publish(Mock(DDSpan), priority, trace) == !singleSpanFull

    then:
    primaryOffers * primary.offer(trace) >> !primaryFull >> true
    0 * secondary.offer(trace) // expect no traces sent to the secondary queue
    singleSpanOffers * spanProcessingWorker.publish(trace) >> !singleSpanFull

    where:
    trace | primaryFull | priority     | primaryOffers | singleSpanOffers | singleSpanFull
    []    | true        | UNSET        | 2             | 0                | false
    []    | true        | SAMPLER_DROP | 0             | 1                | false
    []    | true        | SAMPLER_KEEP | 2             | 0                | false
    []    | true        | SAMPLER_DROP | 0             | 1                | false
    []    | true        | USER_KEEP    | 2             | 0                | false
    []    | false       | UNSET        | 1             | 0                | false
    []    | false       | SAMPLER_DROP | 0             | 1                | false
    []    | false       | SAMPLER_KEEP | 1             | 0                | false
    []    | false       | SAMPLER_DROP | 0             | 1                | false
    []    | false       | USER_KEEP    | 1             | 0                | false
    []    | true        | UNSET        | 2             | 0                | true
    []    | true        | SAMPLER_DROP | 0             | 1                | true
    []    | true        | SAMPLER_KEEP | 2             | 0                | true
    []    | true        | SAMPLER_DROP | 0             | 1                | true
    []    | true        | USER_KEEP    | 2             | 0                | true
    []    | false       | UNSET        | 1             | 0                | true
    []    | false       | SAMPLER_DROP | 0             | 1                | true
    []    | false       | SAMPLER_KEEP | 1             | 0                | true
    []    | false       | SAMPLER_DROP | 0             | 1                | true
    []    | false       | USER_KEEP    | 1             | 0                | true
  }

  def "fast lane strategy sends kept and unset priority traces to the primary queue, dropped traces to the span sampling queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    SpanProcessingWorker spanProcessingWorker = Mock(SpanProcessingWorker)
    PrioritizationStrategy fastLane = FAST_LANE.create(primary, secondary, { false }, spanProcessingWorker)

    when:
    def published = fastLane.publish(Mock(DDSpan), priority, trace)

    then:
    published == publish
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace) >> true // expect no traces sent to the secondary queue
    singleSpanOffers * spanProcessingWorker.publish(trace) >> !singleSpanFull

    where:
    trace | priority     | primaryOffers | singleSpanOffers | singleSpanFull | publish
    []    | UNSET        | 1             | 0                | false          | true
    []    | SAMPLER_DROP | 0             | 1                | false          | true
    []    | SAMPLER_KEEP | 1             | 0                | false          | true
    []    | SAMPLER_DROP | 0             | 1                | false          | true
    []    | USER_KEEP    | 1             | 0                | false          | true
    []    | UNSET        | 1             | 0                | true           | true
    []    | SAMPLER_DROP | 0             | 1                | true           | false // span sampling queue is full
    []    | SAMPLER_KEEP | 1             | 0                | true           | true
    []    | SAMPLER_DROP | 0             | 1                | true           | false // span sampling queue is full
    []    | USER_KEEP    | 1             | 0                | true           | true
  }

  def "FAST_LANE with active dropping policy sends kept and unset priority traces to the primary queue, send to single span sampling all else"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    SpanProcessingWorker spanProcessingWorker = Mock(SpanProcessingWorker)
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, { true }, spanProcessingWorker)

    when:
    boolean published = drop.publish(Mock(DDSpan), priority, trace)

    then:
    published == publish
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace)
    singleSpanOffers * spanProcessingWorker.publish(trace) >> true

    where:
    trace | priority     | primaryOffers | publish | singleSpanOffers
    []    | UNSET        | 1             | true    | 0
    []    | SAMPLER_DROP | 0             | true    | 1
    []    | SAMPLER_KEEP | 1             | true    | 0
    []    | SAMPLER_DROP | 0             | true    | 1
    []    | USER_KEEP    | 1             | true    | 0
  }

  def "drop strategy respects force keep" () {
    setup:
    Queue<Object> primary = Mock(Queue)
    SpanProcessingWorker spanProcessingWorker = Mock(SpanProcessingWorker)
    PrioritizationStrategy drop = strategy.create(primary, null, {
      true
    }, spanProcessingWorker)
    DDSpan root = Mock(DDSpan)
    List<DDSpan> trace = [root]

    when:
    publish = drop.publish(root, SAMPLER_DROP, trace)

    then:
    1 * root.isForceKeep() >> forceKeep
    (forceKeep ? 1 : 0) * primary.offer(trace) >> true
    (forceKeep ? 0 : 1) * spanProcessingWorker.publish(trace) >> !singleSpanFull
    0 * _

    where:
    strategy  | forceKeep | singleSpanFull | publish
    FAST_LANE | true      | true           | true
    FAST_LANE | false     | true           | true
    FAST_LANE | true      | false          | true
    FAST_LANE | false     | false          | false
  }
}

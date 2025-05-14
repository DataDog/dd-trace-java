package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.FlushEvent
import datadog.trace.common.writer.ddagent.PrioritizationStrategy
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.TimeUnit

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.*

class PrioritizationTest extends DDSpecification {

  def "ensure trace strategy tries to send kept and unset priority traces to the primary queue until offer(..) is true, dropped traces to the secondary queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy blocking = ENSURE_TRACE.create(primary, secondary, null, { false })

    when:
    PublishResult publishResult = blocking.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == ENQUEUED_FOR_SERIALIZATION
    primaryOffers * primary.offer(trace) >> !primaryFull >> true
    secondaryOffers * secondary.offer(trace) >> true

    where:
    // spotless:off
    trace | primaryFull | priority     | primaryOffers | secondaryOffers
    []    | true        | UNSET        | 2             | 0
    []    | true        | SAMPLER_DROP | 0             | 1
    []    | true        | SAMPLER_KEEP | 2             | 0
    []    | true        | SAMPLER_DROP | 0             | 1
    []    | true        | USER_KEEP    | 2             | 0
    []    | false       | UNSET        | 1             | 0
    []    | false       | SAMPLER_DROP | 0             | 1
    []    | false       | SAMPLER_KEEP | 1             | 0
    []    | false       | SAMPLER_DROP | 0             | 1
    []    | false       | USER_KEEP    | 1             | 0
    // spotless:on
  }

  def "fast lane strategy sends kept and unset priority traces to the primary queue, dropped traces to the secondary queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy fastLane = FAST_LANE.create(primary, secondary, null, { false })

    when:
    PublishResult publishResult = fastLane.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == DROPPED_BUFFER_OVERFLOW
    primaryOffers * primary.offer(trace)
    secondaryOffers * secondary.offer(trace)

    where:
    // spotless:off
    trace | priority     | primaryOffers | secondaryOffers
    []    | UNSET        | 1             | 0
    []    | SAMPLER_DROP | 0             | 1
    []    | SAMPLER_KEEP | 1             | 0
    []    | SAMPLER_DROP | 0             | 1
    []    | USER_KEEP    | 1             | 0
    // spotless:on
  }

  def "FAST_LANE with active dropping policy sends kept and unset priority traces to the primary queue, drops all else"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, null, { true })

    when:
    PublishResult publishResult = drop.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == expectedResult
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace)

    where:
    // spotless:off
    trace | priority     | primaryOffers | expectedResult
    []    | UNSET        | 1             | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | DROPPED_BY_POLICY
    []    | SAMPLER_KEEP | 1             | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | DROPPED_BY_POLICY
    []    | USER_KEEP    | 1             | ENQUEUED_FOR_SERIALIZATION
    // spotless:on
  }

  def "#strategy strategy flushes primary queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy fastLane = strategy.create(primary, secondary, null, { false })
    when:
    fastLane.flush(100, TimeUnit.MILLISECONDS)
    then:
    1 * primary.offer({ it instanceof FlushEvent }) >> true
    0 * secondary.offer(_)

    where:
    strategy << [FAST_LANE, ENSURE_TRACE]
  }

  def "drop strategy respects force keep" () {
    setup:
    Queue<Object> primary = Mock(Queue)
    PrioritizationStrategy drop = strategy.create(primary, null, null, { true })
    DDSpan root = Mock(DDSpan)
    List<DDSpan> trace = [root]

    when:
    PublishResult publishResult = drop.publish(root, SAMPLER_DROP, trace)

    then:
    publishResult == expectedResult
    1 * root.isForceKeep() >> forceKeep
    (forceKeep ? 1 : 0) * primary.offer(trace) >> true
    0 * _

    where:
    strategy  | forceKeep | expectedResult
    FAST_LANE | true      | ENQUEUED_FOR_SERIALIZATION
    FAST_LANE | false     | DROPPED_BY_POLICY
  }

  def "ensure trace strategy tries to send kept and unset priority traces to the primary queue until offer(..) is true, dropped traces to the span sampling queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    Queue<Object> spanSampling = Mock(Queue)
    PrioritizationStrategy blocking = ENSURE_TRACE.create(primary, secondary, spanSampling, { false })

    when:
    PublishResult publishResult = blocking.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == expectedResult
    primaryOffers * primary.offer(trace) >> !primaryFull >> true
    0 * secondary.offer(trace) // expect no traces sent to the secondary queue
    singleSpanOffers * spanSampling.offer(trace) >> !singleSpanFull

    where:
    trace | primaryFull | priority     | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult
    []    | true        | UNSET        | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | true        | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | true        | SAMPLER_KEEP | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | true        | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | true        | USER_KEEP    | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | false       | UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | false       | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | false       | SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | false       | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | false       | USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | true        | UNSET        | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | true        | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW
    []    | true        | SAMPLER_KEEP | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | true        | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW
    []    | true        | USER_KEEP    | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | false       | UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | false       | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW
    []    | false       | SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | false       | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW
    []    | false       | USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
  }

  def "fast lane strategy sends kept and unset priority traces to the primary queue, dropped traces to the span sampling queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    Queue<Object> spanSampling = Mock(Queue)
    PrioritizationStrategy fastLane = FAST_LANE.create(primary, secondary, spanSampling, { false })

    when:
    PublishResult publishResult = fastLane.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == expectedResult
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace) >> true // expect no traces sent to the secondary queue
    singleSpanOffers * spanSampling.offer(trace) >> !singleSpanFull

    where:
    trace | priority     | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult
    []    | UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION
    []    | UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW // span sampling queue is full
    []    | SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW // span sampling queue is full
    []    | USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION
  }

  def "FAST_LANE with active dropping policy sends kept and unset priority traces to the primary queue, send to single span sampling all else"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    Queue<Object> spanSampling = Mock(Queue)
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, spanSampling, { true })

    when:
    PublishResult publishResult = drop.publish(Mock(DDSpan), priority, trace)

    then:
    publishResult == expectedResult
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace)
    singleSpanOffers * spanSampling.offer(trace) >> true

    where:
    trace | priority     | primaryOffers | singleSpanOffers | expectedResult
    []    | UNSET        | 1             | 0                | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | SAMPLER_KEEP | 1             | 0                | ENQUEUED_FOR_SERIALIZATION
    []    | SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    []    | USER_KEEP    | 1             | 0                | ENQUEUED_FOR_SERIALIZATION
  }

  def "span sampling drop strategy respects force keep" () {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> spanSampling = Mock(Queue)
    PrioritizationStrategy drop = strategy.create(primary, null, spanSampling, { true })
    DDSpan root = Mock(DDSpan)
    List<DDSpan> trace = [root]

    when:
    PublishResult publishResult = drop.publish(root, SAMPLER_DROP, trace)

    then:
    publishResult == expectedResult
    1 * root.isForceKeep() >> forceKeep
    (forceKeep ? 1 : 0) * primary.offer(trace) >> true
    (forceKeep ? 0 : 1) * spanSampling.offer(trace) >> !singleSpanFull
    0 * _

    where:
    strategy  | forceKeep | singleSpanFull | expectedResult
    FAST_LANE | true      | true           | ENQUEUED_FOR_SERIALIZATION
    FAST_LANE | false     | true           | DROPPED_BUFFER_OVERFLOW
    FAST_LANE | true      | false          | ENQUEUED_FOR_SERIALIZATION
    FAST_LANE | false     | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
  }
}

package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.FlushEvent
import datadog.trace.common.writer.ddagent.PrioritizationStrategy
import datadog.trace.core.CoreSpan
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.TimeUnit

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE

class PrioritizationTest extends DDSpecification {

  def "ensure trace strategy tries to send kept and unset priority traces to the primary queue until offer(..) is true, dropped traces to the secondary queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy blocking = ENSURE_TRACE.create(primary, secondary, { false })

    when:
    blocking.publish(Mock(CoreSpan), priority, trace)

    then:
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
    PrioritizationStrategy fastLane = FAST_LANE.create(primary, secondary, { false })

    when:
    fastLane.publish(Mock(CoreSpan), priority, trace)

    then:
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
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, { true })

    when:
    boolean published = drop.publish(Mock(CoreSpan), priority, trace)

    then:
    published == publish
    primaryOffers * primary.offer(trace) >> true
    0 * secondary.offer(trace)

    where:
    // spotless:off
    trace | priority     | primaryOffers | publish
    []    | UNSET        | 1             | true
    []    | SAMPLER_DROP | 0             | false
    []    | SAMPLER_KEEP | 1             | true
    []    | SAMPLER_DROP | 0             | false
    []    | USER_KEEP    | 1             | true
    // spotless:on
  }

  def "#strategy strategy flushes primary queue"() {
    setup:
    Queue<Object> primary = Mock(Queue)
    Queue<Object> secondary = Mock(Queue)
    PrioritizationStrategy fastLane = strategy.create(primary, secondary, { false })
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
    PrioritizationStrategy drop = strategy.create(primary, null, {
      true
    })
    CoreSpan root = Mock(CoreSpan)
    List<CoreSpan> trace = [root]

    when:
    drop.publish(root, SAMPLER_DROP, trace)
    then:
    1 * root.isForceKeep() >> forceKeep
    (forceKeep ? 1 : 0) * primary.offer(trace) >> true
    0 * _

    where:
    strategy | forceKeep
    FAST_LANE | true
    FAST_LANE | false
  }
}

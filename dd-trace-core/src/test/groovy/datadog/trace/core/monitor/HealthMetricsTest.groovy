package datadog.trace.core.monitor

import com.timgroup.statsd.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore
import spock.lang.Subject

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class HealthMetricsTest extends DDSpecification {
  def statsD = Mock(StatsDClient)

  @Subject
  def healthMetrics = new HealthMetrics(statsD)

  // This fails because DDAgentWriter isn't an interface and the mock doesn't prevent the call.
  @Ignore
  def "test onStart"() {
    setup:
    def writer = Mock(DDAgentWriter)

    when:
    healthMetrics.onStart(writer)

    then:
    1 * writer.getCapacity() >> capacity
    0 * _

    where:
    capacity = ThreadLocalRandom.current().nextInt()
  }

  def "test onShutdown"() {
    when:
    healthMetrics.onShutdown(true)

    then:
    0 * _
  }

  def "test onPublish"() {
    setup:
    def statsD = Mock(StatsDClient)
    def healthMetrics = new HealthMetrics(statsD, 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onPublish(trace, samplingPriority)
    Thread.sleep(110)

    then:
    1 * statsD.count('queue.enqueued.traces', 1, "priority:" + priorityName)
    (trace.isEmpty() ? 0 : 1) * statsD.count('queue.enqueued.spans', trace.size())
    0 * _
    cleanup:
    healthMetrics.close()

    where:
    trace        | samplingPriority              | priorityName
    []           | PrioritySampling.USER_DROP    | "user_drop"
    [null, null] | PrioritySampling.USER_DROP    | "user_drop"
    []           | PrioritySampling.SAMPLER_KEEP | "sampler_keep"
    [null, null] | PrioritySampling.SAMPLER_KEEP | "sampler_keep"
  }

  def "test onFailedPublish"() {
    setup:
    def statsD = Mock(StatsDClient)
    def healthMetrics = new HealthMetrics(statsD, 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onFailedPublish(samplingPriority)
    Thread.sleep(110)

    then:
    1 * statsD.count('queue.dropped.traces', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()

    where:
    samplingPriority << [PrioritySampling.SAMPLER_KEEP, PrioritySampling.USER_KEEP, PrioritySampling.USER_DROP, PrioritySampling.SAMPLER_DROP, PrioritySampling.UNSET]
  }

  def "test onScheduleFlush"() {
    when:
    healthMetrics.onScheduleFlush(true)

    then:
    0 * _
  }

  def "test onFlush"() {
    when:
    healthMetrics.onFlush(true)

    then:
    0 * _
  }

  def "test onSerialize"() {
    when:
    healthMetrics.onSerialize(bytes)

    then:
    1 * statsD.count('queue.enqueued.bytes', bytes)
    0 * _

    where:
    bytes = ThreadLocalRandom.current().nextInt(10000)
  }

  def "test onFailedSerialize"() {
    when:
    healthMetrics.onFailedSerialize(null, null)

    then:
    0 * _
  }

  def "test onSend"() {
    when:
    healthMetrics.onSend(traceCount, sendSize, response)

    then:
    1 * statsD.incrementCounter('api.requests.total')
    1 * statsD.count('flush.traces.total', traceCount)
    1 * statsD.count('flush.bytes.total', sendSize)
    if (response.exception()) {
      1 * statsD.incrementCounter('api.errors.total')
    }
    if (response.status()) {
      1 * statsD.incrementCounter('api.responses.total', ["status:${response.status()}"])
    }
    0 * _

    where:
    response << [
      DDAgentApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100)),
      DDAgentApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100)),
      DDAgentApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100), new Throwable()),
      DDAgentApi.Response.failed(new Throwable()),
    ]

    traceCount = ThreadLocalRandom.current().nextInt(1, 100)
    sendSize = ThreadLocalRandom.current().nextInt(1, 100)
  }

  def "test onFailedSend"() {
    when:
    healthMetrics.onFailedSend(traceCount, sendSize, response)

    then:
    1 * statsD.incrementCounter('api.requests.total')
    1 * statsD.count('flush.traces.total', traceCount)
    1 * statsD.count('flush.bytes.total', sendSize)
    if (response.exception()) {
      1 * statsD.incrementCounter('api.errors.total')
    }
    if (response.status()) {
      1 * statsD.incrementCounter('api.responses.total', ["status:${response.status()}"])
    }
    0 * _

    where:
    response << [
      DDAgentApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100)),
      DDAgentApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100)),
      DDAgentApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100), new Throwable()),
      DDAgentApi.Response.failed(new Throwable()),
    ]

    traceCount = ThreadLocalRandom.current().nextInt(1, 100)
    sendSize = ThreadLocalRandom.current().nextInt(1, 100)
  }
}

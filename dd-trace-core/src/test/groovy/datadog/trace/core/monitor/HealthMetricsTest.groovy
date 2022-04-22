package datadog.trace.core.monitor

import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.RemoteApi
import datadog.trace.common.writer.RemoteWriter
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class HealthMetricsTest extends DDSpecification {
  def statsD = Mock(StatsDClient)

  @Subject
  def healthMetrics = new HealthMetrics(statsD)

  // This fails because RemoteWriter isn't an interface and the mock doesn't prevent the call.
  @Ignore
  def "test onStart"() {
    setup:
    def writer = Mock(RemoteWriter)

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
    def latch = new CountDownLatch(trace.isEmpty() ? 1 : 2)
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onPublish(trace, samplingPriority)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('queue.enqueued.traces', 1, "priority:" + priorityName)
    (trace.isEmpty() ? 0 : 1) * statsD.count('queue.enqueued.spans', trace.size())
    0 * _
    cleanup:
    healthMetrics.close()

    where:
    // spotless:off
    trace        | samplingPriority              | priorityName
    []           | PrioritySampling.USER_DROP    | "user_drop"
    [null, null] | PrioritySampling.USER_DROP    | "user_drop"
    []           | PrioritySampling.SAMPLER_KEEP | "sampler_keep"
    [null, null] | PrioritySampling.SAMPLER_KEEP | "sampler_keep"
    // spotless:on
  }

  def "test onFailedPublish"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onFailedPublish(samplingPriority)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('queue.dropped.traces', 1, _)
    0 * _

    cleanup:
    healthMetrics.close()

    where:
    samplingPriority << [
      PrioritySampling.SAMPLER_KEEP,
      PrioritySampling.USER_KEEP,
      PrioritySampling.USER_DROP,
      PrioritySampling.SAMPLER_DROP,
      PrioritySampling.UNSET
    ]
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
      RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100)),
      RemoteApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100)),
      RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100), new Throwable()),
      RemoteApi.Response.failed(new Throwable()),
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
      RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100)),
      RemoteApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100)),
      RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100), new Throwable()),
      RemoteApi.Response.failed(new Throwable()),
    ]

    traceCount = ThreadLocalRandom.current().nextInt(1, 100)
    sendSize = ThreadLocalRandom.current().nextInt(1, 100)
  }

  private static class Latched implements StatsDClient {
    final StatsDClient delegate
    final CountDownLatch latch

    Latched(StatsDClient delegate, CountDownLatch latch) {
      this.delegate = delegate
      this.latch = latch
    }

    @Override
    void incrementCounter(String metricName, String... tags) {
      try {
        delegate.incrementCounter(metricName, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void count(String metricName, long delta, String... tags) {
      try {
        delegate.count(metricName, delta, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void gauge(String metricName, long value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void gauge(String metricName, double value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void histogram(String metricName, long value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void histogram(String metricName, double value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void serviceCheck(String serviceCheckName, String status, String message, String... tags) {
      try {
        delegate.serviceCheck(serviceCheckName, status, message, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void error(Exception error) {
      try {
        delegate.error(error)
      } finally {
        latch.countDown()
      }
    }

    @Override
    int getErrorCount() {
      try {
        return delegate.getErrorCount()
      } finally {
        latch.countDown()
      }
    }

    @Override
    void close() {
      try {
        delegate.close()
      } finally {
        latch.countDown()
      }
    }
  }
}

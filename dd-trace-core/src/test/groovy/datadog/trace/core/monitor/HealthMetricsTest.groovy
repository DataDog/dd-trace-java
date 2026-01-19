package datadog.trace.core.monitor

import datadog.metrics.statsd.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.RemoteApi
import datadog.trace.common.writer.RemoteWriter
import spock.lang.Ignore
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit


class HealthMetricsTest extends Specification {
  def statsD = Mock(StatsDClient)

  @Subject
  def healthMetrics = new TracerHealthMetrics(statsD)

  // This fails because RemoteWriter isn't an interface and the mock doesn't prevent the call.
  @Ignore
  def "test onStart"() {
    setup:
    def writer = Mock(RemoteWriter)
    def capacity = ThreadLocalRandom.current().nextInt()

    when:
    healthMetrics.onStart(writer)

    then:
    1 * writer.getCapacity() >> capacity
    0 * _
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
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
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
    def latch = new CountDownLatch(2)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onFailedPublish(samplingPriority,spanCount)
    latch.await(2, TimeUnit.SECONDS)

    then:
    1 * statsD.count('queue.dropped.traces', 1, samplingTag)
    1 * statsD.count('queue.dropped.spans', 1, samplingTag)
    0 * _

    cleanup:
    healthMetrics.close()

    where:
    // spotless:off
    samplingPriority              | samplingTag             | spanCount
    PrioritySampling.SAMPLER_KEEP | "priority:sampler_keep" | 1
    PrioritySampling.USER_KEEP    | "priority:user_keep"    | 1
    PrioritySampling.USER_DROP    | "priority:user_drop"    | 1
    PrioritySampling.SAMPLER_DROP | "priority:sampler_drop" | 1
    PrioritySampling.UNSET        | "priority:unset"        | 1
    // spotless:off


  }

  def "test onPartialPublish"() {
    setup:
    def latch = new CountDownLatch(2)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onPartialPublish(droppedSpans)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('queue.partial.traces', 1)
    1 * statsD.count('queue.dropped.spans', droppedSpans, samplingPriority)
    0 * _

    cleanup:
    healthMetrics.close()

    where:
    // spotless:off
    droppedSpans | traces | samplingPriority
    1            | 4      | ['priority:sampler_drop']
    42           | 1      | ['priority:sampler_drop']
    3            | 5      | ['priority:sampler_drop']
    // spotless:on
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
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    def bytes = ThreadLocalRandom.current().nextInt(10000)
    healthMetrics.start()

    when:
    healthMetrics.onSerialize(bytes)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('queue.enqueued.bytes', bytes)
    0 * _

    cleanup:
    healthMetrics.close()
  }

  def "test onFailedSerialize"() {
    when:
    healthMetrics.onFailedSerialize(null, null)

    then:
    0 * _
  }

  def "test onSend #iterationIndex"() {
    setup:
    def latch = new CountDownLatch(3 + (response.exception().present ? 1 : 0) + (response.status().present ? 1 : 0))
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onSend(traceCount, sendSize, response)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('api.requests.total', 1)
    1 * statsD.count('flush.traces.total', traceCount)
    1 * statsD.count('flush.bytes.total', sendSize)
    if (response.exception().present) {
      1 * statsD.count('api.errors.total', 1)
    }
    if (response.status().present) {
      1 * statsD.incrementCounter('api.responses.total', ["status:${response.status().asInt}"])
    }
    0 * _

    cleanup:
    healthMetrics.close()

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

  def "test onFailedSend #iterationIndex"() {
    setup:
    def latch = new CountDownLatch(3 + (response.exception().present ? 1 : 0) + (response.status().present ? 1 : 0))
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()

    when:
    healthMetrics.onFailedSend(traceCount, sendSize, response)
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsD.count('api.requests.total', 1)
    1 * statsD.count('flush.traces.total', traceCount)
    1 * statsD.count('flush.bytes.total', sendSize)
    if (response.exception().present) {
      1 * statsD.count('api.errors.total', 1)
    }
    if (response.status().present) {
      1 * statsD.incrementCounter('api.responses.total', ["status:${response.status().asInt}"])
    }
    0 * _

    cleanup:
    healthMetrics.close()

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

  def "test onCreateTrace"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onCreateTrace()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("trace.pending.created", 1, _)
    cleanup:
    healthMetrics.close()
  }

  def "test onCreateSpan"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onCreateSpan()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.pending.created", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onCancelContinuation"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onCancelContinuation()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.continuations.canceled", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onFinishContinuation"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onFinishContinuation()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.continuations.finished", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onSingleSpanSample"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onSingleSpanSample()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.sampling.sampled", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onSingleSpanUnsampled"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onSingleSpanUnsampled()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.sampling.unsampled", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onFinishSpan"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onFinishSpan()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.pending.finished", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onActivateScope"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onActivateScope()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("scope.activate.count", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onCloseScope"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onCloseScope()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("scope.close.count", 1, _)
    cleanup:
    healthMetrics.close()
  }
  def "test onScopeCloseError"() {
    setup:
    def latch = new CountDownLatch(1 + (manual ? 1 : 0))
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onScopeCloseError(manual)
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("scope.close.error", 1, _)
    if (manual) {
      1 * statsD.count("scope.user.close.error", 1, _)
    }
    cleanup:
    healthMetrics.close()
    where:
    manual << [false, true]
  }
  def "test onScopeStackOverflow"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onScopeStackOverflow()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("scope.error.stack-overflow", 1, _)
    cleanup:
    healthMetrics.close()
  }

  def "test onLongRunningUpdate"() {
    setup:
    def latch = new CountDownLatch(3)
    def healthMetrics = new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onLongRunningUpdate(3,10,1)
    latch.await(10, TimeUnit.SECONDS)
    then:
    1 * statsD.count("long-running.write", 10, _)
    1 * statsD.count("long-running.dropped", 3, _)
    1 * statsD.count("long-running.expired", 1, _)
    cleanup:
    healthMetrics.close()
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
    void distribution(String metricName, long value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags)
      } finally {
        latch.countDown()
      }
    }

    @Override
    void distribution(String metricName, double value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags)
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

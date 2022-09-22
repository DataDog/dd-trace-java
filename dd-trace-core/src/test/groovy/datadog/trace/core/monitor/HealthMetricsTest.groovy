package datadog.trace.core.monitor

import datadog.trace.api.DDId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.RemoteApi
import datadog.trace.common.writer.RemoteWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.PendingTrace
import datadog.trace.core.PendingTraceBuffer
import datadog.trace.core.scopemanager.ContinuableScopeManager
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE

class HealthMetricsTest extends DDSpecification {
  def statsD = Mock(StatsDClient)
  def buffer = PendingTraceBuffer.delaying(SystemTimeSource.INSTANCE)
  def bufferSpy = Spy(buffer)

  def tracer = Mock(CoreTracer)
  def factory = new PendingTrace.Factory(tracer, bufferSpy, SystemTimeSource.INSTANCE, false,statsD)
  @Subject
  def healthMetrics = new HealthMetrics(statsD)

  def setupEnv(){
    injectSysConfig("agent.port" , "777")
    injectSysConfig("trace.agent.port" , "9999")
    injectSysConfig(HEALTH_METRICS_ENABLED , "true")
    injectSysConfig(SERVICE_NAME , "test")
    injectSysConfig(ENV , "test")
    injectSysConfig(PRIORITY_SAMPLING , "true")
    injectSysConfig(WRITER_TYPE , "DDAgentWriter")
    injectSysConfig(SERVICE_MAPPING , "a:one, a:two, a:three")
    injectSysConfig(SPAN_TAGS , "a:one, a:two, a:three")
    injectSysConfig(HEADER_TAGS , "a:one, a:two, a:three")
    injectSysConfig(AGENT_UNIX_DOMAIN_SOCKET , "asdf")
  }

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

  def "test onCreateTrace"() {
    setup:
    def latch = new CountDownLatch(1)
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
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
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
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
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
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
    def healthMetrics = new HealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)
    healthMetrics.start()
    when:
    healthMetrics.onFinishContinuation()
    latch.await(5, TimeUnit.SECONDS)
    then:
    1 * statsD.count("span.continuations.finished", 1, _)
    cleanup:
    healthMetrics.close()
  }

  //spotless:off
  @Ignore
  def "full health metrics test"(){
    setup:
    setupEnv()
    def writer = Mock(DDAgentWriter)
    def statsSpy = Spy(StatsDClient)
    CoreTracer tracer = CoreTracer.builder().statsDClient(statsD)
      .writer(writer)
      .scopeManager(
        new ContinuableScopeManager(100,statsSpy,false,false)
      ).build()
    when:
    def span = tracer.buildSpan("test").start()
    then:
    1 * statsSpy.count(_,_,_)
    when:
    span.finish()
    then:
    1 * span.context().trace.healthMetrics.onCreateTrace()

  }
  //spotless:on
  @Ignore
  def "metric test"(){
    setup:
    setupEnv()
    def writer = Mock(DDAgentWriter)
    when:
    def span = factory.create(new DDId(0,"test"))
    then:
    1 * statsD.count(_,_,_)
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

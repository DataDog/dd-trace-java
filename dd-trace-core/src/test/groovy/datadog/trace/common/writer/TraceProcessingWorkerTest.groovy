package datadog.trace.common.writer

import datadog.trace.api.StatsDClient
import datadog.trace.core.DDSpan
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE

class TraceProcessingWorkerTest extends DDSpecification {

  @Shared
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)

  def flushCountingPayloadDispatcher(AtomicInteger flushCounter) {
    PayloadDispatcher dispatcher = Mock(PayloadDispatcher)
    dispatcher.flush() >> {
      flushCounter.incrementAndGet()
    }
    return dispatcher
  }

  def "heartbeats should be triggered automatically when enabled"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(HealthMetrics),
      flushCountingPayloadDispatcher(flushCount), {
        false
      },
      FAST_LANE,
      1,
      TimeUnit.NANOSECONDS) // stop heartbeats from being throttled

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically"
    conditions.eventually {
      flushCount.get() > 0
    }

    cleanup:
    worker.close()
  }

  def "heartbeats should occur at least once per second when not throttled"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(HealthMetrics),
      flushCountingPayloadDispatcher(flushCount),
      {
        false
      },
      FAST_LANE,
      1,
      TimeUnit.NANOSECONDS) // stop heartbeats from being throttled
    def timeConditions = new PollingConditions(timeout: 1, initialDelay: 1, factor: 1.25)

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically approximately once per second"
    timeConditions.eventually {
      flushCount.get() > 1
    }

    cleanup:
    worker.close()
  }

  def "a flush should clear the primary queue"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(HealthMetrics),
      flushCountingPayloadDispatcher(flushCount),
      {
        false
      },
      FAST_LANE,
      100, TimeUnit.SECONDS) // prevent heartbeats from helping the flush happen

    when: "there is pending work it is completed before a flush"
    // processing this span will throw an exception, but it should be caught
    // and not disrupt the flush
    worker.primaryQueue.offer([Mock(DDSpan)])
    worker.start()
    boolean flushed = worker.flush(10, TimeUnit.SECONDS)

    then: "the flush succeeds, triggers a dispatch, and the queue is empty"
    flushed
    flushCount.get() == 1
    worker.primaryQueue.isEmpty()

    cleanup:
    worker.close()
  }

  def "should report failure if serialization fails"() {
    setup:
    Throwable theError = new IllegalStateException("thrown by test")
    PayloadDispatcher throwingDispatcher = Mock(PayloadDispatcher)
    throwingDispatcher.addTrace(_) >> {
      throw theError
    }
    AtomicInteger errorReported = new AtomicInteger()
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    healthMetrics.onFailedSerialize(_, theError) >> {
      // do this manually with a counter, despite spock's
      // lovely syntactical sugar so we don't have a race
      // condition induced flaky test. All we care about
      // is that an error was reported and that it was the
      // right one
      errorReported.incrementAndGet()
    }
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      throwingDispatcher, {
        false
      }, FAST_LANE,
      100, TimeUnit.SECONDS) // prevent heartbeats from helping the flush happen
    worker.start()

    when: "a trace is processed but can't be passed on"
    worker.publish(Mock(DDSpan), priority, [Mock(DDSpan)])

    then: "the error is reported to the monitor"
    conditions.eventually {
      1 == errorReported.get()
    }

    cleanup:
    worker.close()

    where:
    priority << [SAMPLER_DROP, USER_DROP, SAMPLER_KEEP, USER_KEEP, UNSET]
  }

  def "traces should be processed"() {
    setup:
    AtomicInteger acceptedCount = new AtomicInteger()
    PayloadDispatcher countingDispatcher = Mock(PayloadDispatcher)
    countingDispatcher.addTrace(_) >> {
      acceptedCount.getAndIncrement()
    }
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      countingDispatcher, {
        false
      }, FAST_LANE, 100, TimeUnit.SECONDS)
    // prevent heartbeats from helping the flush happen
    worker.start()

    when: "traces are submitted"
    int submitted = 0
    for (int i = 0; i < traceCount; ++i) {
      submitted += worker.publish(Mock(DDSpan), priority, [Mock(DDSpan)]) ? 1 : 0
    }

    then: "traces are passed through unless rejected on submission"
    0 * healthMetrics.onFailedSerialize(_, _)
    conditions.eventually {
      submitted == acceptedCount.get()
    }

    cleanup:
    worker.close()


    where:
    priority     | traceCount | strategy
    SAMPLER_DROP | 1          | FAST_LANE
    USER_DROP    | 1          | FAST_LANE
    SAMPLER_KEEP | 1          | FAST_LANE
    USER_KEEP    | 1          | FAST_LANE
    UNSET        | 1          | FAST_LANE
    SAMPLER_DROP | 10         | FAST_LANE
    USER_DROP    | 10         | FAST_LANE
    SAMPLER_KEEP | 10         | FAST_LANE
    USER_KEEP    | 10         | FAST_LANE
    UNSET        | 10         | FAST_LANE
    SAMPLER_DROP | 20         | FAST_LANE
    USER_DROP    | 20         | FAST_LANE
    SAMPLER_KEEP | 20         | FAST_LANE
    USER_KEEP    | 20         | FAST_LANE
    UNSET        | 20         | FAST_LANE
    SAMPLER_DROP | 100        | FAST_LANE
    USER_DROP    | 100        | FAST_LANE
    SAMPLER_KEEP | 100        | FAST_LANE
    USER_KEEP    | 100        | FAST_LANE
    UNSET        | 100        | FAST_LANE

  }

  def "flush of full queue after worker thread stopped will not flush but will return"() {
    setup:
    PayloadDispatcher countingDispatcher = Mock(PayloadDispatcher)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      countingDispatcher, {
        false
      }, FAST_LANE, 100, TimeUnit.SECONDS)
    worker.start()
    worker.close()
    int queueSize = 0
    while (worker.primaryQueue.offer([Mock(DDSpan)])) {
      queueSize++
    }

    when:
    boolean flushed = worker.flush(1, TimeUnit.SECONDS)
    then:
    !flushed
  }

}

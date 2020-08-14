package datadog.trace.common.writer

import datadog.trace.common.writer.ddagent.PayloadDispatcher
import datadog.trace.common.writer.ddagent.TraceProcessingWorker
import datadog.trace.core.DDSpan
import datadog.trace.core.monitor.Monitor
import datadog.trace.core.processor.TraceProcessor
import datadog.trace.util.test.DDSpecification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TraceProcessingWorkerTest extends DDSpecification {

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)

  def flushCountingPayloadDispatcher(AtomicInteger flushCounter) {
    PayloadDispatcher dispatcher = Mock(PayloadDispatcher)
    dispatcher.flush() >> {
      flushCounter.incrementAndGet()
    }
    return dispatcher
  }

  def "heartbeats should trigger flushes"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(Monitor),
      flushCountingPayloadDispatcher(flushCount),
      1,
      TimeUnit.NANOSECONDS, // stop heartbeats from being throttled
      false) // prevent scheduled heartbeats from interfering with the test

    when: "do ${heartbeatCount} heartbeats"
    worker.start()
    for (int i = 0; i < heartbeatCount; ++i) {
      worker.heartbeat()
    }

    then:
    "${heartbeatCount} flushes must be triggered"
    conditions.eventually {
      heartbeatCount == flushCount.get()
    }

    cleanup:
    worker.close()

    where:
    heartbeatCount << [1, 2, 10]
  }

  def "heartbeats should be triggered automatically when enabled"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(Monitor),
      flushCountingPayloadDispatcher(flushCount),
      1,
      TimeUnit.NANOSECONDS, // stop heartbeats from being throttled
      true)

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically"
    conditions.eventually {
      flushCount.get() > 0
    }

    cleanup:
    worker.close()
  }

  def "heartbeats should occur approximately once per second when not throttled"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(Monitor),
      flushCountingPayloadDispatcher(flushCount),
      1,
      TimeUnit.NANOSECONDS, // stop heartbeats from being throttled
      true)
    def timeConditions = new PollingConditions(timeout: 4, initialDelay: 3, factor: 1.25)

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically approximately once per second"
    timeConditions.eventually {
      flushCount.get() > 3
    }

    cleanup:
    worker.close()
  }

  def "a flush should clear the primary queue"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(Monitor),
      flushCountingPayloadDispatcher(flushCount), 100, TimeUnit.SECONDS,
      false) // prevent heartbeats from helping the flush happen

    when: "there is pending work it is completed before a flush"
    // processing this span will throw an exception, but it should be caught
    // and not disrupt the flush
    worker.primaryQueue.offer([Mock(DDSpan)])
    worker.start()
    boolean flushed = worker.flush(10, TimeUnit.SECONDS)

    then: "the flush succeeds, triggers a dispatch, and the queue is empty"
    flushed
    worker.primaryQueue.isEmpty()

    cleanup:
    worker.close()
  }

  def "should report failure if rules can't be applied to trace" () {
    setup:
    Throwable theError = new IllegalStateException("thrown by test")
    TraceProcessor throwingTraceProcessor = Mock(TraceProcessor)
    throwingTraceProcessor.onTraceComplete(_) >> {
      throw theError
    }
    AtomicInteger errorReported = new AtomicInteger()
    Monitor monitor = Mock(Monitor)
    monitor.onFailedSerialize(_, theError) >> {
      // do this manually with a counter, despite spock's
      // lovely syntactical sugar so we don't have a race
      // condition induced flaky test. All we care about
      // is that an error was reported and that it was the
      // right one
      errorReported.incrementAndGet()
    }
    TraceProcessingWorker worker = new TraceProcessingWorker(10, monitor,
      Mock(PayloadDispatcher), throwingTraceProcessor, 100, TimeUnit.SECONDS,
      false) // prevent heartbeats from helping the flush happen
    worker.start()

    when: "a trace is processed but rules can't be applied"
    worker.publish([Mock(DDSpan)])

    then: "the error is reported to the monitor"
    conditions.eventually {
      1 == errorReported.get()
    }

    cleanup: worker.close()
  }


  def "should report failure if serialization fails" () {
    setup:
    Throwable theError = new IllegalStateException("thrown by test")
    PayloadDispatcher throwingDispatcher = Mock(PayloadDispatcher)
    throwingDispatcher.addTrace(_) >> {
      throw theError
    }
    AtomicInteger errorReported = new AtomicInteger()
    Monitor monitor = Mock(Monitor)
    monitor.onFailedSerialize(_, theError) >> {
      // do this manually with a counter, despite spock's
      // lovely syntactical sugar so we don't have a race
      // condition induced flaky test. All we care about
      // is that an error was reported and that it was the
      // right one
      errorReported.incrementAndGet()
    }
    TraceProcessingWorker worker = new TraceProcessingWorker(10, monitor,
      throwingDispatcher, Stub(TraceProcessor), 100, TimeUnit.SECONDS,
      false) // prevent heartbeats from helping the flush happen
    worker.start()

    when: "a trace is processed but can't be passed on"
    worker.publish([Mock(DDSpan)])

    then: "the error is reported to the monitor"
    conditions.eventually {
      1 == errorReported.get()
    }

    cleanup: worker.close()
  }

  def "traces should be processed"() {
    setup:
    AtomicInteger acceptedCount = new AtomicInteger()
    PayloadDispatcher countingDispatcher = Mock(PayloadDispatcher)
    countingDispatcher.addTrace(_) >> {
      acceptedCount.getAndIncrement()
    }
    Monitor monitor = Mock(Monitor)
    TraceProcessingWorker worker = new TraceProcessingWorker(10, monitor,
      countingDispatcher, Stub(TraceProcessor), 100, TimeUnit.SECONDS,
      false) // prevent heartbeats from helping the flush happen
    worker.start()

    when: "traces are submitted"
    int submitted = 0
    for (int i = 0; i < traceCount; ++i) {
      submitted += worker.publish([Mock(DDSpan)]) ? 1 : 0
    }

    then: "traces are passed through unless rejected on submission"
    0 * monitor.onFailedSerialize(_, _)
    conditions.eventually {
      submitted == acceptedCount.get()
    }

    cleanup:
    worker.close()


    where:

    traceCount << [1, 10, 20, 100]

  }

}

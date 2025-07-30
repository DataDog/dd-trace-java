package datadog.trace.common.writer

import datadog.trace.common.sampling.SingleSpanSampler
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult
import datadog.trace.core.CoreSpan
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor
import datadog.trace.test.util.DDSpecification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION

class TraceProcessingWorkerTest extends DDSpecification {

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)

  def flushCountingPayloadDispatcher(AtomicInteger flushCounter) {
    PayloadDispatcherImpl dispatcher = Mock(PayloadDispatcherImpl)
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
      TimeUnit.NANOSECONDS,
      null
      ) // stop heartbeats from being throttled

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically"
    conditions.eventually {
      assert flushCount.get() > 0
    }

    cleanup:
    worker.close()
  }

  def "heartbeats should occur at least once per second when not throttled"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(HealthMetrics),
      flushCountingPayloadDispatcher(flushCount), {
        false
      },
      FAST_LANE,
      1,
      TimeUnit.NANOSECONDS,
      null
      ) // stop heartbeats from being throttled
    def timeConditions = new PollingConditions(timeout: 1, initialDelay: 1, factor: 1.25)

    when: "processor is started"
    worker.start()

    then: "heartbeat occurs automatically approximately once per second"
    timeConditions.eventually {
      assert flushCount.get() > 1
    }

    cleanup:
    worker.close()
  }

  def "a flush should clear the primary queue"() {
    setup:
    AtomicInteger flushCount = new AtomicInteger()
    TraceProcessingWorker worker = new TraceProcessingWorker(10, Stub(HealthMetrics),
      flushCountingPayloadDispatcher(flushCount), {
        false
      },
      FAST_LANE,
      100, TimeUnit.SECONDS, null) // prevent heartbeats from helping the flush happen

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
    PayloadDispatcherImpl throwingDispatcher = Mock(PayloadDispatcherImpl)
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
      100, TimeUnit.SECONDS, null) // prevent heartbeats from helping the flush happen
    worker.start()

    when: "a trace is processed but can't be passed on"
    worker.publish(Mock(DDSpan), priority, [Mock(DDSpan)])

    then: "the error is reported to the monitor"
    conditions.eventually {
      assert 1 == errorReported.get()
    }

    cleanup:
    worker.close()

    where:
    priority << [SAMPLER_DROP, USER_DROP, SAMPLER_KEEP, USER_KEEP, UNSET]
  }

  def "trace should be post-processed"() {
    setup:
    AtomicInteger acceptedCount = new AtomicInteger()
    PayloadDispatcherImpl countingDispatcher = Mock(PayloadDispatcherImpl)
    countingDispatcher.addTrace(_) >> {
      acceptedCount.getAndIncrement()
    }
    HealthMetrics healthMetrics = Mock(HealthMetrics)

    def span1 = DDSpan.create("test", 0, Mock(DDSpanContext) {
      getTraceCollector() >> Mock(PendingTrace) {
        getCurrentTimeNano() >> 0
      }
    }, [])
    def processedSpan1 = false

    // Span 2 - should NOT be post-processed
    def span2 = DDSpan.create("test", 0, Mock(DDSpanContext) {
      getTraceCollector() >> Mock(PendingTrace) {
        getCurrentTimeNano() >> 0
      }
    }, [])
    def processedSpan2 = false

    SpanPostProcessor.Holder.INSTANCE = Mock(SpanPostProcessor) {
      process(span1, _) >> { processedSpan1 = true }
      process(span2, _) >> { processedSpan2 = true }
    }

    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      countingDispatcher, {
        false
      }, FAST_LANE, 100, TimeUnit.SECONDS, null)
    worker.start()

    when: "traces are submitted"
    worker.publish(span1, SAMPLER_KEEP, [span1, span2])
    worker.publish(span2, SAMPLER_KEEP, [span1, span2])

    then: "traces are passed through unless rejected on submission"
    conditions.eventually {
      assert processedSpan1
      assert processedSpan2
    }

    cleanup:
    SpanPostProcessor.Holder.INSTANCE = SpanPostProcessor.Holder.NOOP
    worker.close()
  }

  def "traces should be processed"() {
    setup:
    AtomicInteger acceptedCount = new AtomicInteger()
    PayloadDispatcherImpl countingDispatcher = Mock(PayloadDispatcherImpl)
    countingDispatcher.addTrace(_) >> {
      acceptedCount.getAndIncrement()
    }
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      countingDispatcher, {
        false
      }, FAST_LANE, 100, TimeUnit.SECONDS, null)
    // prevent heartbeats from helping the flush happen
    worker.start()

    when: "traces are submitted"
    int submitted = 0
    for (int i = 0; i < traceCount; ++i) {
      PublishResult publishResult = worker.publish(Mock(DDSpan), priority, [Mock(DDSpan)])
      submitted += publishResult == ENQUEUED_FOR_SERIALIZATION ? 1 : 0
    }

    then: "traces are passed through unless rejected on submission"
    0 * healthMetrics.onFailedSerialize(_, _)
    conditions.eventually {
      assert submitted == acceptedCount.get()
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
    PayloadDispatcherImpl countingDispatcher = Mock(PayloadDispatcherImpl)
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics,
      countingDispatcher, {
        false
      }, FAST_LANE, 100, TimeUnit.SECONDS, null)
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

  def "send unsampled traces to the SpanProcessingWorker and expect only sampled spans dispatched when dropping policy is active"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    AtomicInteger acceptedCount = new AtomicInteger()
    AtomicInteger acceptedSpanCount = new AtomicInteger()
    PayloadDispatcherImpl countingDispatcher = Mock(PayloadDispatcherImpl)
    countingDispatcher.addTrace(_) >> {
      List traceList = it[0]
      acceptedSpanCount.getAndAdd(traceList.size())
      acceptedCount.getAndIncrement()
    }
    AtomicInteger sampledSpansCount = new AtomicInteger()
    SingleSpanSampler singleSpanSampler = new SingleSpanSampler() {
        int counter = 0
        boolean setSamplingPriority(CoreSpan span) {
          if (counter++ % 2 == 0) {
            sampledSpansCount.incrementAndGet()
            return true
          }
          // drop every other trace span
          return false
        }
      }
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics, countingDispatcher, { true }, FAST_LANE, 100, TimeUnit.SECONDS, singleSpanSampler)
    worker.start()

    when: "traces are submitted"
    for (int i = 0; i < traceCount; ++i) {
      worker.publish(trace.get(0), priority, trace)
    }

    then: "traces are passed through unless rejected on submission"
    conditions.eventually {
      assert acceptedTraces == acceptedCount.get()
      assert acceptedSpans == acceptedSpanCount.get()
      assert sampledSingleSpans == sampledSpansCount.get()
    }

    cleanup:
    worker.close()

    where:
    priority     | traceCount | acceptedTraces | acceptedSpans | sampledSingleSpans | trace
    SAMPLER_DROP | 1          | 1              | 1             | 1                  | [Mock(DDSpan)]
    USER_DROP    | 1          | 1              | 1             | 1                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 1          | 1              | 2             | 2                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 1          | 1              | 2             | 2                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 1          | 1              | 3             | 3                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 1              | 1             | 1                  | [Mock(DDSpan)] // expectedTraceCount = 1 b/o 2nd trace's only span gets unsampled
    SAMPLER_DROP | 2          | 2              | 2             | 2                  | [Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 2              | 3             | 3                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 2          | 2              | 4             | 4                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 2              | 5             | 5                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 10         | 5              | 5             | 5                  | [Mock(DDSpan)] // expectedTraceCount = 5 b/o every 2nd trace's only span gets unsampled
    USER_DROP    | 10         | 10             | 10            | 10                 | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 10         | 10             | 15            | 15                 | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 10         | 10             | 20            | 20                 | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 10         | 10             | 25            | 25                 | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    // do not dispatch kept traces to the single span sampler
    SAMPLER_KEEP | 1          | 1              | 1             | 0                  | [Mock(DDSpan)]
    USER_KEEP    | 1          | 1              | 2             | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 1          | 1              | 3             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 1          | 1              | 4             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 1          | 1              | 5             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 2             | 0                  | [Mock(DDSpan)]
    SAMPLER_KEEP | 2          | 2              | 4             | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 6             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 2          | 2              | 8             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 10            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 10            | 0                  | [Mock(DDSpan)]
    USER_KEEP    | 10         | 10             | 20            | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 30            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 10         | 10             | 40            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 50            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
  }

  def "send unsampled traces to the SpanProcessingWorker and expect all spans dispatched when dropping policy is inactive"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    AtomicInteger chunksCount = new AtomicInteger()
    AtomicInteger spansCount = new AtomicInteger()
    PayloadDispatcherImpl countingDispatcher = Mock(PayloadDispatcherImpl)
    countingDispatcher.addTrace(_) >> {
      List traceList = it[0]
      spansCount.getAndAdd(traceList.size())
      chunksCount.getAndIncrement()
    }
    AtomicInteger sampledSpansCount = new AtomicInteger()
    SingleSpanSampler singleSpanSampler = new SingleSpanSampler() {
        int counter = 0
        boolean setSamplingPriority(CoreSpan span) {
          if (counter++ % 2 == 0) {
            sampledSpansCount.incrementAndGet()
            return true
          }
          // drop every other trace span
          return false
        }
      }
    TraceProcessingWorker worker = new TraceProcessingWorker(10, healthMetrics, countingDispatcher, { false }, FAST_LANE, 100, TimeUnit.SECONDS, singleSpanSampler)
    worker.start()

    when: "traces are submitted"
    for (int i = 0; i < traceCount; ++i) {
      worker.publish(trace.get(0), priority, trace)
    }

    then: "traces are passed through unless rejected on submission"
    conditions.eventually {
      assert expectedChunks == chunksCount.get()
      assert expectedSpans == spansCount.get()
      assert sampledSingleSpans == sampledSpansCount.get()
    }

    cleanup:
    worker.close()

    where:
    priority     | traceCount | expectedChunks | expectedSpans | sampledSingleSpans | trace
    SAMPLER_DROP | 1          | 1              | 1             | 1                  | [Mock(DDSpan)]
    USER_DROP    | 1          | 2              | 2             | 1                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 1          | 2              | 3             | 2                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 1          | 2              | 4             | 2                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 1          | 2              | 5             | 3                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 2              | 2*1           | 1                  | [Mock(DDSpan)]
    SAMPLER_DROP | 2          | 2*2            | 2*2           | 2                  | [Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 2*2            | 2*3           | 3                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 2          | 2*2            | 2*4           | 4                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 2          | 2*2            | 2*5           | 5                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 10         | 10             | 10            | 10/2*1             | [Mock(DDSpan)]
    SAMPLER_DROP | 10         | 10*2           | 20            | 10/2*2             | [Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 10         | 10*2           | 30            | 10/2*3             | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_DROP | 10         | 10*2           | 40            | 10/2*4             | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_DROP    | 10         | 10*2           | 50            | 10/2*5             | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    // do not dispatch kept traces to the single span sampler
    SAMPLER_KEEP | 1          | 1              | 1             | 0                  | [Mock(DDSpan)]
    USER_KEEP    | 1          | 1              | 2             | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 1          | 1              | 3             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 1          | 1              | 4             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 1          | 1              | 5             | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 2*1           | 0                  | [Mock(DDSpan)]
    SAMPLER_KEEP | 2          | 2              | 2*2           | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 2*3           | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 2          | 2              | 2*4           | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 2          | 2              | 2*5           | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 10            | 0                  | [Mock(DDSpan)]
    USER_KEEP    | 10         | 10             | 20            | 0                  | [Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 30            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    USER_KEEP    | 10         | 10             | 40            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
    SAMPLER_KEEP | 10         | 10             | 50            | 0                  | [Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan), Mock(DDSpan)]
  }
}

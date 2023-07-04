package datadog.trace.agent.test.timer

import datadog.trace.api.profiling.QueueTiming
import datadog.trace.api.profiling.Timer
import datadog.trace.api.profiling.Timing
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static datadog.trace.api.profiling.Timer.TimerType.QUEUEING

class TestTimer implements Timer {

  private static final Logger logger = LoggerFactory.getLogger(TestTimer)

  @Override
  Timing start(TimerType type) {
    if (type == QUEUEING) {
      return new TestQueueTiming()
    }
    return Timing.NoOp.INSTANCE
  }

  class TestQueueTiming implements QueueTiming {

    Class<?> task
    Class<?> scheduler
    final Thread origin
    final long start

    TestQueueTiming() {
      origin = Thread.currentThread()
      start = System.currentTimeMillis()
    }

    @Override
    void setTask(Class<?> task) {
      this.task = task
    }

    @Override
    void setScheduler(Class<?> scheduler) {
      this.scheduler = scheduler
    }

    @Override
    void close() {
      AgentSpan span = AgentTracer.activeSpan()
      long activeSpanId = span == null ? 0 : span.getSpanId()
      long duration = System.currentTimeMillis() - start
      logger.debug("task {} with spanId={} migrated from {} to {} in {}ms, scheduled by {}",
        task.simpleName, activeSpanId, origin.name, Thread.currentThread().name, duration, scheduler.name)
    }
  }
}

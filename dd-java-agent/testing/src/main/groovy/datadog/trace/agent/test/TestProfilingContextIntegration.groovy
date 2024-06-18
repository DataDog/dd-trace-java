package datadog.trace.agent.test

import datadog.trace.api.EndpointTracker
import datadog.trace.api.profiling.ProfilingContextAttribute
import datadog.trace.api.profiling.ProfilingScope
import datadog.trace.api.profiling.QueueTiming
import datadog.trace.api.profiling.Timing
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper
import org.eclipse.jetty.util.ConcurrentHashSet
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.BlockingDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.api.profiling.Timer.TimerType.QUEUEING

class TestProfilingContextIntegration implements ProfilingContextIntegration {
  final AtomicInteger attachments = new AtomicInteger()
  final AtomicInteger detachments = new AtomicInteger()
  final AtomicInteger counter = new AtomicInteger()
  final BlockingDeque<Timing> closedTimings = new LinkedBlockingDeque<>()
  final Logger logger = LoggerFactory.getLogger(TestProfilingContextIntegration)
  final ConcurrentHashMap<CharSequence, AtomicInteger> spanIds = new ConcurrentHashMap<>()
  final ThreadLocal<CharSequence> currentSpan = ThreadLocal.withInitial {
    ""
  }
  @Override
  void onAttach() {
    attachments.incrementAndGet()
  }

  @Override
  void onDetach() {
    detachments.incrementAndGet()
  }

  int getActivationBalance(CharSequence spanName) {
    return spanIds.get(spanName)?.get() ?: -1
  }

  void clear() {
    attachments.set(0)
    detachments.set(0)
  }

  @Override
  void setContext(ProfilerContext profilerContext) {
    new Throwable("mount " + profilerContext.operationName).printStackTrace(System.err)
    spanIds.computeIfAbsent(profilerContext.operationName, {
      return new AtomicInteger()
    }).getAndIncrement()
    currentSpan.set(profilerContext.operationName)
  }

  @Override
  void clearContext() {
    def spanName = currentSpan.get()
    if (spanName?.length() != 0) {
      spanIds.getOrDefault(spanName, new AtomicInteger()).getAndDecrement()
      currentSpan.remove()
    }
  }

  @Override
  String name() {
    return "test"
  }

  @Override
  ProfilingContextAttribute createContextAttribute(String attribute) {
    return ProfilingContextAttribute.NoOp.INSTANCE
  }

  @Override
  ProfilingScope newScope() {
    return ProfilingScope.NO_OP
  }

  @Override
  void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
  }

  @Override
  EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return EndpointTracker.NO_OP
  }

  @Override
  Timing start(TimerType type) {
    if (type == QUEUEING) {
      return new TestQueueTiming()
    }
    return Timing.NoOp.INSTANCE
  }

  boolean isBalanced() {
    return counter.get() == 0
  }

  class TestQueueTiming implements QueueTiming {

    Class<?> task
    Class<?> scheduler
    final Thread origin
    final long start

    TestQueueTiming() {
      counter.incrementAndGet()
      origin = Thread.currentThread()
      start = System.currentTimeMillis()
    }

    @Override
    void setTask(Object task) {
      this.task = TaskWrapper.getUnwrappedType(task)
    }

    @Override
    void setScheduler(Class<?> scheduler) {
      this.scheduler = scheduler
    }

    @Override
    void close() {
      counter.decrementAndGet()
      AgentSpan span = AgentTracer.activeSpan()
      long activeSpanId = span == null ? 0 : span.getSpanId()
      long duration = System.currentTimeMillis() - start
      logger.debug("task {} with spanId={} migrated from {} to {} in {}ms, scheduled by {}",
        task.simpleName, activeSpanId, origin.name, Thread.currentThread().name, duration, scheduler.name)
      closedTimings.offer(this)
    }


    @Override
    String toString() {
      return task.name
    }
  }
}

package datadog.trace.bootstrap.instrumentation.java.concurrent

import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import datadog.trace.test.util.DDSpecification

/**
 * Tests for the queue-wait TaskBlock emission added to {@link Wrapper}.
 *
 * <p>When a task is submitted to a thread pool, {@link Wrapper#wrap} captures TSC ticks via
 * {@link ProfilingContextIntegration#getCurrentTicks()} and stores them as {@code submissionTicks}.
 * When the task eventually executes on a worker thread, {@link Wrapper#run} emits a TaskBlock
 * event covering the queue-wait interval (submission → execution), making this hand-off delay
 * visible in the causal graph.
 */
class WrapperSubmissionTicksTest extends DDSpecification {

  AgentTracer.TracerAPI originalTracer
  ProfilingContextIntegration mockProfiling

  def setup() {
    originalTracer = AgentTracer.get()
    mockProfiling = Mock(ProfilingContextIntegration) {
      name() >> "test"
      newScopeState(_) >> datadog.trace.api.Stateful.DEFAULT
    }
    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getProfilingContext() >> mockProfiling
    }
    AgentTracer.forceRegister(mockTracer)
  }

  def cleanup() {
    AgentTracer.forceRegister(originalTracer)
  }

  def "TaskBlock is emitted when submissionTicks is positive"() {
    given:
    long spanId = 111L
    long rootSpanId = 222L
    long ticks = 42L

    def mockCtx = Mock(TestSpanContext) {
      getSpanId() >> spanId
      getRootSpanId() >> rootSpanId
    }
    def mockSpan = Mock(AgentSpan) {
      context() >> mockCtx
    }
    def mockScope = Mock(AgentScope) {
      span() >> mockSpan
    }
    def mockContinuation = Mock(AgentScope.Continuation) {
      activate() >> mockScope
    }
    def wrapper = new Wrapper<>({} as Runnable, mockContinuation, ticks)

    when:
    wrapper.run()

    then:
    // Queue-wait TaskBlock: startTicks=ticks, spanId and rootSpanId from context,
    // blocker=0 (no specific object), unblockingSpanId=0 (no unblocking span)
    1 * mockProfiling.recordTaskBlock(ticks, spanId, rootSpanId, 0L, 0L)
  }

  def "TaskBlock is NOT emitted when submissionTicks is zero (profiler inactive at wrap time)"() {
    given:
    def mockCtx = Mock(TestSpanContext) {
      getSpanId() >> 111L
      getRootSpanId() >> 222L
    }
    def mockSpan = Mock(AgentSpan) {
      context() >> mockCtx
    }
    def mockScope = Mock(AgentScope) {
      span() >> mockSpan
    }
    def mockContinuation = Mock(AgentScope.Continuation) {
      activate() >> mockScope
    }
    def wrapper = new Wrapper<>({} as Runnable, mockContinuation, 0L)

    when:
    wrapper.run()

    then:
    0 * mockProfiling.recordTaskBlock(*_)
  }

  def "ComparableRunnable forwards submissionTicks to Wrapper superclass"() {
    given:
    long ticks = 99L
    long spanId = 333L
    long rootSpanId = 444L

    def mockCtx = Mock(TestSpanContext) {
      getSpanId() >> spanId
      getRootSpanId() >> rootSpanId
    }
    def mockSpan = Mock(AgentSpan) {
      context() >> mockCtx
    }
    def mockScope = Mock(AgentScope) {
      span() >> mockSpan
    }
    def mockContinuation = Mock(AgentScope.Continuation) {
      activate() >> mockScope
    }
    def task = new ComparableTask()
    def wrapper = new ComparableRunnable(task, mockContinuation, ticks)

    when:
    wrapper.run()

    then:
    1 * mockProfiling.recordTaskBlock(ticks, spanId, rootSpanId, 0L, 0L)
  }

  static class ComparableTask implements Runnable, Comparable<ComparableTask> {
    @Override
    void run() {}

    @Override
    int compareTo(ComparableTask o) {
      0
    }
  }

  /**
   * Combined interface so Spock can create a single mock that satisfies both the
   * {@code AgentSpan.context()} return type ({@code AgentSpanContext}) and the
   * {@code instanceof ProfilerContext} check inside {@code Wrapper.run()}.
   */
  interface TestSpanContext extends ProfilerContext, AgentSpanContext {}
}

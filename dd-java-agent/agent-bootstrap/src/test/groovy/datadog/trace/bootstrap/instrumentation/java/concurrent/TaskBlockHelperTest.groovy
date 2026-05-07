package datadog.trace.bootstrap.instrumentation.java.concurrent

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import spock.lang.Specification

class TaskBlockHelperTest extends Specification {
  static final long SPAN_ID = 0xDEADBEEFL
  static final long ROOT_SPAN_ID = 0xCAFEBABEL
  static final long START_TICKS = 42_000_000L
  static final long BLOCKER = 1234L

  def profiling = Mock(ProfilingContextIntegration)
  def span = Mock(AgentSpan)
  def ctx = Mock(ProfilerSpanContext)
  def nonProfilerCtx = Mock(AgentSpanContext)

  def setup() {
    span.context() >> ctx
    ctx.getSpanId() >> SPAN_ID
    ctx.getRootSpanId() >> ROOT_SPAN_ID
    profiling.getCurrentTicks() >> START_TICKS
  }

  def "capture returns null without profiling context"() {
    expect:
    TaskBlockHelper.capture(BLOCKER, null, span) == null
  }

  def "capture returns null without active span"() {
    expect:
    TaskBlockHelper.capture(BLOCKER, profiling, null) == null
  }

  def "capture returns null when span context is not profiler context"() {
    setup:
    def nonProfilerSpan = Mock(AgentSpan)
    nonProfilerSpan.context() >> nonProfilerCtx

    expect:
    TaskBlockHelper.capture(BLOCKER, profiling, nonProfilerSpan) == null
  }

  def "capture records active span and entry timing"() {
    setup:
    long before = System.nanoTime()

    when:
    def state = TaskBlockHelper.capture(BLOCKER, profiling, span)

    then:
    state != null
    state.profiling == profiling
    state.startTicks == START_TICKS
    state.startNanos >= before
    state.startNanos <= System.nanoTime()
    state.spanId == SPAN_ID
    state.rootSpanId == ROOT_SPAN_ID
    state.blocker == BLOCKER
  }

  def "finish ignores null state"() {
    when:
    TaskBlockHelper.finish(null)

    then:
    0 * profiling._
  }

  def "finish ignores too-short intervals"() {
    setup:
    def state = new TaskBlockHelper.State(
      profiling,
      START_TICKS,
      System.nanoTime() + 60_000_000_000L,
      SPAN_ID,
      ROOT_SPAN_ID,
      BLOCKER)

    when:
    TaskBlockHelper.finish(state)

    then:
    0 * profiling.recordTaskBlock(_, _, _, _, _)
  }

  def "finish emits task block for eligible interval"() {
    setup:
    def state = new TaskBlockHelper.State(
      profiling,
      START_TICKS,
      System.nanoTime() - 2 * TaskBlockHelper.MIN_TASK_BLOCK_NANOS,
      SPAN_ID,
      ROOT_SPAN_ID,
      BLOCKER)

    when:
    TaskBlockHelper.finish(state)

    then:
    1 * profiling.recordTaskBlock(START_TICKS, SPAN_ID, ROOT_SPAN_ID, BLOCKER, 0L)
  }

  private interface ProfilerSpanContext extends AgentSpanContext, ProfilerContext {}
}

package datadog.trace.core.jfr.openjdk

import datadog.trace.api.GlobalTracer
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.core.util.SystemAccess
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.time.Duration

import static datadog.trace.api.Checkpointer.CPU

@Requires({
  jvm.java11Compatible
})
class ScopeEventTest extends DDSpecification {
  private static final Duration SLEEP_DURATION = Duration.ofMillis(200)

  def tracer

  def setup() {
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    injectSysConfig(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, "true")
    injectSysConfig(ProfilingConfig.PROFILING_CHECKPOINTS_RECORD_CPU_TIME, "true")
    tracer = CoreTracer.builder().writer(new ListWriter()).build()
    GlobalTracer.forceRegister(tracer)
    tracer.addScopeListener(new ScopeEventFactory())
  }

  def cleanup() {
    tracer?.close()
  }

  // TODO more tests around CPU time (mocking out the SystemAccess class)
  def "Scope event is written with thread CPU time"() {
    setup:
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = JfrHelper.stopRecording(recording)
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.eventType.name == "datadog.Scope"
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
    event.getLong("cpuTime") > 0
  }

  def "Scope event is written without thread CPU time"() {
    setup:
    SystemAccess.disableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = JfrHelper.stopRecording(recording)
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.eventType.name == "datadog.Scope"
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
    event.getLong("cpuTime") == Long.MIN_VALUE
  }

  def "Scope event is written after continuation activation"() {
    setup:
    def recording = JfrHelper.startRecording()

    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope parentScope = tracer.activateSpan(span)
    parentScope.setAsyncPropagation(true)
    TraceScope.Continuation continuation = ((TraceScope) parentScope).capture()

    when:
    TraceScope scope = continuation.activate()
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = JfrHelper.stopRecording(recording)
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.eventType.name == "datadog.Scope"
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
  }

  def "Scope events are written - two deep"() {
    setup:
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    AgentSpan span2 = tracer.buildSpan("test").start()
    AgentScope scope2 = tracer.activateSpan(span2)
    sleep(SLEEP_DURATION.toMillis())
    scope2.close()
    span2.finish()

    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    span.finish()

    def events = JfrHelper.stopRecording(recording)

    then:
    events.size() == 2
    events.each {
      assert it.eventType.name == "datadog.Scope"
    }

    with(events[0]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
      duration >= SLEEP_DURATION
      duration < SLEEP_DURATION * 2
    }

    with(events[1]) {
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
      duration >= SLEEP_DURATION * 3
      getLong("cpuTime") > events[0].getLong("cpuTime")
    }
  }

  def "Scope events are written - two deep, two wide"() {
    setup:
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    AgentSpan span2 = tracer.buildSpan("test").start()
    AgentScope scope2 = tracer.activateSpan(span2)
    sleep(SLEEP_DURATION.toMillis())
    scope2.close()
    span2.finish()

    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    span.finish()

    AgentSpan span3 = tracer.buildSpan("test").start()
    AgentScope scope3 = tracer.activateSpan(span3)
    sleep(SLEEP_DURATION.toMillis())

    AgentSpan span4 = tracer.buildSpan("test").start()
    AgentScope scope4 = tracer.activateSpan(span4)
    sleep(SLEEP_DURATION.toMillis())
    scope4.close()
    span4.finish()

    sleep(SLEEP_DURATION.toMillis())
    scope3.close()
    span3.finish()

    def events = JfrHelper.stopRecording(recording)

    then:
    events.size() == 4
    events.each {
      assert it.eventType.name == "datadog.Scope"
    }

    with(events[0]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
      duration >= SLEEP_DURATION
      duration < SLEEP_DURATION * 2
    }

    with(events[1]) {
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
      duration >= SLEEP_DURATION * 3
      getLong("cpuTime") > events[0].getLong("cpuTime")
    }

    with(events[2]) {
      getLong("traceId") == span4.context().traceId.toLong()
      getLong("spanId") == span4.context().spanId.toLong()
      duration >= SLEEP_DURATION
      duration < SLEEP_DURATION * 2
    }

    with(events[3]) {
      getLong("traceId") == span3.context().traceId.toLong()
      getLong("spanId") == span3.context().spanId.toLong()
      duration >= SLEEP_DURATION * 3
      getLong("cpuTime") > events[2].getLong("cpuTime")
    }
  }

  def "Test out of order scope closing"() {
    setup:
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    AgentSpan span2 = tracer.buildSpan("test").start()
    AgentScope scope2 = tracer.activateSpan(span2)

    scope.close()
    span.finish()

    sleep(SLEEP_DURATION.toMillis())

    scope2.close()
    span2.finish()

    def events = JfrHelper.stopRecording(recording)

    then:
    events.size() == 2
    events.each {
      assert it.eventType.name == "datadog.Scope"
      assert it.duration >= SLEEP_DURATION
    }

    with(events[0]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
    }

    with(events[1]) {
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
    }
  }

  def "Events are not created when profiling is not enabled"() {
    setup:
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "false")
    def noProfilingTracer = CoreTracer.builder().writer(new ListWriter()).build()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = noProfilingTracer.buildSpan("test").start()
    AgentScope scope = noProfilingTracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    scope.close()
    span.finish()

    def events = JfrHelper.stopRecording(recording)

    then:
    events.isEmpty()

    cleanup:
    noProfilingTracer.close()
  }

  def "checkpoint events written when checkpointer registered"() {
    setup:
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()
    tracer.registerCheckpointer(new JFRCheckpointer())

    when: "span goes through lifecycle without activation"
    def span = tracer.startSpan("test")
    span.startThreadMigration()
    span.finishThreadMigration()
    span.finish()
    then: "checkpoints emitted"
    def events = JfrHelper.stopRecording(recording)
    events.size() == 4
    events.each {
      assert it.eventType.name == "datadog.Checkpoint"
      assert it.getLong("traceId") == span.getTraceId().toLong()
      assert it.getLong("spanId") == span.getSpanId().toLong()
      int flags = it.getInt("flags")
      long cpuTime = it.getLong("cpuTime")
      if ((flags & CPU) != 0) {
        assert cpuTime > 0
      } else {
        assert cpuTime == 0L
      }

    }
  }
}

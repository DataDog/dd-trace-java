package datadog.trace.core.jfr.openjdk


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

@Requires({ jvm.java11Compatible })
class ScopeEventTest extends DDSpecification {
  private static final Duration SLEEP_DURATION = Duration.ofSeconds(1)

  def tracer

  def setup() {
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    tracer = CoreTracer.builder().writer(new ListWriter()).build()
  }

  def cleanup() {
    tracer?.close()
  }

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
    event.getLong("cpuTime") != Long.MIN_VALUE

    cleanup:
    SystemAccess.disableJmx()
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

    cleanup:
    SystemAccess.disableJmx()
  }

  def "Scope event is written after continuation activation"() {
    setup:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope parentScope = tracer.activateSpan(span)
    parentScope.setAsyncPropagation(true)
    TraceScope.Continuation continuation = ((TraceScope) parentScope).capture()
    def recording = JfrHelper.startRecording()

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

  def "Scope event is written at each scope transition"() {
    setup:
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
    events.size() == 3
    events.each {
      assert it.eventType.name == "datadog.Scope"
      assert it.duration >= SLEEP_DURATION
    }

    with(events[0]) {
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
    }

    with(events[1]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
    }

    with(events[2]) {
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
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
      getLong("traceId") == span.context().traceId.toLong()
      getLong("spanId") == span.context().spanId.toLong()
    }

    with(events[1]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
    }
  }

  def "Events are not created when not recording - linear activation"() {
    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    scope.close()
    span.finish()

    def recording = JfrHelper.startRecording()

    AgentSpan span2 = tracer.buildSpan("test").start()
    AgentScope scope2 = tracer.activateSpan(span2)
    sleep(SLEEP_DURATION.toMillis())

    scope2.close()
    span2.finish()


    def events = JfrHelper.stopRecording(recording)

    then:
    events.size() == 1
    events.each {
      assert it.eventType.name == "datadog.Scope"
      assert it.duration >= SLEEP_DURATION
    }

    with(events[0]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
    }
  }

  def "Events are not created when not recording - linear with close outside recording"() {
    when:
    def recording = JfrHelper.startRecording()
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    def events = JfrHelper.stopRecording(recording)

    scope.close()
    span.finish()

    def recording2 = JfrHelper.startRecording()
    AgentSpan span2 = tracer.buildSpan("test").start()
    AgentScope scope2 = tracer.activateSpan(span2)
    sleep(SLEEP_DURATION.toMillis())

    scope2.close()
    span2.finish()


    def events2 = JfrHelper.stopRecording(recording2)

    then:
    events.size() == 0

    and:
    events2.size() == 1
    events2.each {
      assert it.eventType.name == "datadog.Scope"
      assert it.duration >= SLEEP_DURATION
    }
    with(events2[0]) {
      getLong("traceId") == span2.context().traceId.toLong()
      getLong("spanId") == span2.context().spanId.toLong()
    }
  }

  def "Events are not created when not recording - stacked activation"() {
    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    def recording = JfrHelper.startRecording()

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
}

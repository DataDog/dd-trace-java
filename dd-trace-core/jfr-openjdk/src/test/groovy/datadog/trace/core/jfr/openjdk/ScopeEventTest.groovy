package datadog.trace.core.jfr.openjdk

import datadog.trace.api.GlobalTracer
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.core.util.SystemAccess
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.time.Duration
import java.util.stream.Collectors

@Requires({
  jvm.java11Compatible
})
class ScopeEventTest extends DDSpecification {
  private static final Duration SLEEP_DURATION = Duration.ofMillis(200)

  def tracer

  def setup() {
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    tracer = CoreTracer.builder().writer(new ListWriter()).build()
    GlobalTracer.forceRegister(tracer)
  }

  def cleanup() {
    tracer?.close()
  }

  def filterEvents(events, eventTypeNames) {
    return events.stream()
      .filter({ it.eventType.name in eventTypeNames })
      .collect(Collectors.toList())
  }

  def addScopeEventFactory(hotspots = true, checkpoints = true) {
    injectSysConfig(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, String.valueOf(hotspots))
    injectSysConfig(ProfilingConfig.PROFILING_CHECKPOINTS_RECORD_CPU_TIME, String.valueOf(checkpoints))
    tracer.addScopeListener(new ScopeEventFactory())
  }

  // TODO more tests around CPU time (mocking out the SystemAccess class)
  def "Default scope event is written without thread CPU time"() {
    setup:
    addScopeEventFactory(false)
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
    event.getLong("cpuTime") == Long.MIN_VALUE
  }

  def "Scope event is written with thread CPU time"() {
    setup:
    addScopeEventFactory()
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
    event.getLong("cpuTime") > 0
  }

  def "Scope event is written without thread CPU time"() {
    setup:
    addScopeEventFactory()
    SystemAccess.disableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope scope = tracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
    event.getLong("cpuTime") == Long.MIN_VALUE
  }

  def "Scope event is written after continuation activation"() {
    setup:
    addScopeEventFactory()
    def recording = JfrHelper.startRecording()

    AgentSpan span = tracer.buildSpan("test").start()
    AgentScope parentScope = tracer.activateSpan(span)
    parentScope.setAsyncPropagation(true)
    TraceScope.Continuation continuation = ((TraceScope) parentScope).capture()

    when:
    TraceScope scope = continuation.activate()
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.duration >= SLEEP_DURATION
    event.getLong("traceId") == span.context().traceId.toLong()
    event.getLong("spanId") == span.context().spanId.toLong()
  }

  def "Scope events are written - two deep"() {
    setup:
    addScopeEventFactory()
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

    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])

    then:
    events.size() == 2

    println "span ends: ${events[0].getEndTime()}, ${events[1].getEndTime()}"
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
    addScopeEventFactory()
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

    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])

    then:
    events.size() == 4

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
    addScopeEventFactory()
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

    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])

    then:
    events.size() == 2
    events.each {
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
    addScopeEventFactory()
    def noProfilingTracer = CoreTracer.builder().writer(new ListWriter()).build()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = noProfilingTracer.buildSpan("test").start()
    AgentScope scope = noProfilingTracer.activateSpan(span)
    sleep(SLEEP_DURATION.toMillis())

    scope.close()
    span.finish()

    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Scope"])

    then:
    events.isEmpty()

    cleanup:
    noProfilingTracer.close()
  }

  def "checkpoint events written when checkpointer registered"() {
    setup:
    addScopeEventFactory()
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()
    tracer.registerCheckpointer(new JFRCheckpointer(null, ConfigProvider.getInstance()))

    when: "span goes through lifecycle without activation"
    AgentSpan span = tracer.startSpan("test")
    span.startThreadMigration()
    span.finishThreadMigration()
    span.setResourceName("foo")
    span.finish()
    then: "checkpoints emitted"
    def events = filterEvents(JfrHelper.stopRecording(recording), ["datadog.Checkpoint", "datadog.Endpoint"])
    events.size() == 5
    events.each {
      assert it.getLong("localRootSpanId") == span.getLocalRootSpan().getSpanId().toLong()
      if (it.eventType.name == "datadog.Checkpoint") {
        assert it.getLong("spanId") == span.getSpanId().toLong()
      } else {
        it.getString("endpoint") == "foo"
      }
    }
  }
}

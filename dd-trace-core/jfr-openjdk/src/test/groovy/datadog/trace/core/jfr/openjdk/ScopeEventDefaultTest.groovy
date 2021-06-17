package datadog.trace.core.jfr.openjdk

import datadog.trace.api.GlobalTracer
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.util.SystemAccess
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.time.Duration
import java.util.stream.Collectors

@Requires({
  jvm.java11Compatible
})
/*
  This needs to be done as a separate test because it is not possible to change system config between test cases
 */
class ScopeEventDefaultTest extends DDSpecification {
  private static final Duration SLEEP_DURATION = Duration.ofMillis(200)

  def tracer

  def setup() {
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    tracer = CoreTracer.builder().writer(new ListWriter()).build()
    GlobalTracer.forceRegister(tracer)
    tracer.addScopeListener(new ScopeEventFactory())
  }

  def cleanup() {
    tracer?.close()
  }

  def filterEvents(events, eventTypeNames) {
    return events.stream()
      .filter({it.eventType.name in eventTypeNames})
      .collect(Collectors.toList())
  }

  def "Default scope event is written without thread CPU time"() {
    setup:
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
}

package datadog.trace.core.jfr.openjdk

import datadog.trace.api.DDId
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.ProfilingConfig
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpanContext
import datadog.trace.core.util.SystemAccess
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires

import java.time.Duration

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME

@Requires({ jvm.java11Compatible })
class ScopeEventTest extends DDSpecification {
  private static final Duration SLEEP_DURATION = Duration.ofSeconds(1)

  def writer = new ListWriter()
  def tracer = CoreTracer.builder().serviceName(DEFAULT_SERVICE_NAME).writer(writer).build()

  def parentContext =
    new DDSpanContext(
      DDId.from(123),
      DDId.from(432),
      DDId.from(222),
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      [:],
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.from(123)))
  def builder = tracer.buildSpan("test operation")
    .asChildOf(parentContext)
    .withServiceName("test service")
    .withResourceName("test resource")

  def cleanup() {
    tracer?.close()
  }

  def "Scope event is written with thread CPU time"() {
    setup:
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = builder.start()
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

  def "Scope event is written without thread CPU time - profiling enabled"() {
    setup:
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "true")
    SystemAccess.disableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = builder.start()
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

  def "Scope event is written without thread CPU time - profiling disabled"() {
    setup:
    injectSysConfig(ProfilingConfig.PROFILING_ENABLED, "false")
    injectSysConfig(GeneralConfig.HEALTH_METRICS_ENABLED, "false")
    SystemAccess.enableJmx()
    def recording = JfrHelper.startRecording()

    when:
    AgentSpan span = builder.start()
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
    AgentSpan span = builder.start()
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
}

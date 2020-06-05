package datadog.trace.core.jfr.openjdk

import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.Config
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.CoreTracer
import datadog.trace.api.DDId
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.util.ThreadCpuTimeAccess
import datadog.trace.util.test.DDSpecification
import spock.lang.Requires

import java.time.Duration

import static datadog.trace.api.Config.DEFAULT_SERVICE_NAME

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
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      [:],
      false,
      "fakeType",
      null,
      new PendingTrace(tracer, DDId.from(123)),
      tracer,
      [:])
  def builder = tracer.buildSpan("test operation")
    .asChildOf(parentContext)
    .withServiceName("test service")
    .withResourceName("test resource")

  def "Scope event is written with thread CPU time"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }
    ThreadCpuTimeAccess.enableJmx()
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
    ThreadCpuTimeAccess.disableJmx()
  }

  def "Scope event is written without thread CPU time - profiling enabled"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "true")
    }
    ThreadCpuTimeAccess.disableJmx()
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
    ThreadCpuTimeAccess.disableJmx()
  }

  def "Scope event is written without thread CPU time - profiling disabled"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${Config.PROFILING_ENABLED}", "false")
    }
    ThreadCpuTimeAccess.enableJmx()
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
    ThreadCpuTimeAccess.disableJmx()
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

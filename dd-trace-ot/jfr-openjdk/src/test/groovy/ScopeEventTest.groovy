import datadog.opentracing.DDSpanContext
import datadog.opentracing.DDTracer
import datadog.opentracing.PendingTrace
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import io.opentracing.Scope
import io.opentracing.Span
import spock.lang.Requires
import spock.lang.Specification

import java.time.Duration

import static datadog.trace.api.Config.DEFAULT_SERVICE_NAME

@Requires({ jvm.java11Compatible })
class ScopeEventTest extends Specification {

  private static final int IDS_RADIX = 16
  private static final Duration SLEEP_DURATION = Duration.ofSeconds(1)

  def writer = new ListWriter()
  def tracer = new DDTracer(DEFAULT_SERVICE_NAME, writer, new RateByServiceSampler(), [:])

  def parentContext =
    new DDSpanContext(
      123,
      432,
      222,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      [:],
      false,
      "fakeType",
      null,
      new PendingTrace(tracer, 123),
      tracer,
      [:])
  def builder = tracer.buildSpan("test operation")
    .asChildOf(parentContext)
    .withServiceName("test service")
    .withResourceName("test resource")

  def "Scope event is written"() {
    setup:
    def recording = JfrHelper.startRecording()

    when:
    Scope scope = builder.startActive(false)
    Span span = scope.span()
    sleep(SLEEP_DURATION.toMillis())
    scope.close()
    def events = JfrHelper.stopRecording(recording)
    span.finish()

    then:
    events.size() == 1
    def event = events[0]
    event.eventType.name == "datadog.Scope"
    event.duration >= SLEEP_DURATION
    event.getString("traceId") == span.context().traceId.toString(IDS_RADIX)
    event.getString("spanId") == span.context().spanId.toString(IDS_RADIX)
    event.getString("parentId") == span.context().parentId.toString(IDS_RADIX)
    event.getString("serviceName") == "test service"
    event.getString("resourceName") == "test resource"
    event.getString("operationName") == "test operation"
  }

  def "Scope event is written after continuation activation"() {
    setup:
    TraceScope parentScope = builder.startActive(false)
    parentScope.setAsyncPropagation(true)
    Span span = parentScope.span()
    TraceScope.Continuation continuation = parentScope.capture()
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
    event.getString("traceId") == span.context().traceId.toString(IDS_RADIX)
    event.getString("spanId") == span.context().spanId.toString(IDS_RADIX)
    event.getString("parentId") == span.context().parentId.toString(IDS_RADIX)
    event.getString("serviceName") == "test service"
    event.getString("resourceName") == "test resource"
    event.getString("operationName") == "test operation"
  }
}

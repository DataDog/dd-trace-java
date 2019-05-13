import datadog.opentracing.DDSpanContext
import datadog.opentracing.DDTracer
import datadog.opentracing.PendingTrace
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.common.writer.ListWriter
import spock.lang.Requires
import spock.lang.Specification

import java.time.Duration

import static datadog.trace.api.Config.DEFAULT_SERVICE_NAME

@Requires({ jvm.java11Compatible })
class SpanEventTest extends Specification {

  private static final Duration SLEEP_DURATION = Duration.ofSeconds(1)

  def writer = new ListWriter()
  def tracer = new DDTracer(DEFAULT_SERVICE_NAME, writer, new RateByServiceSampler(), [:])
  def parentContext =
    new DDSpanContext(
      "123",
      "432",
      "222",
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      [:],
      false,
      "fakeType",
      null,
      new PendingTrace(tracer, "123", [:]),
      tracer)

  def "Span event is written"() {
    setup:
    def builder = tracer.buildSpan("test operation")
      .asChildOf(parentContext)
      .withServiceName("test service")
      .withResourceName("test resource")
    def recording = JfrHelper.startRecording()

    when:
    def span = builder.start()
    sleep(SLEEP_DURATION.toMillis())
    span.finish()
    def events = JfrHelper.stopRecording(recording)

    then:
    events.size() == 1
    def event = events[0]
    event.eventType.name == "datadog.Span"
    event.duration >= SLEEP_DURATION
    event.getString("traceId") == span.context().traceId
    event.getString("spanId") == span.context().spanId
    event.getString("parentId") == span.context().parentId
    event.getString("serviceName") == "test service"
    event.getString("resourceName") == "test resource"
    event.getString("operationName") == "test operation"
  }
}

import datadog.trace.agent.test.AgentTestRunner
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.BaggageEntryMetadata
import io.opentelemetry.context.Context
import spock.lang.Subject

class OpenTelemetry1BaggageTest extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("some-instrumentation")

  def "test baggage"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def aSpan = builder.startSpan()
    def current = aSpan.makeCurrent()

    when:
    def aBaggage = Baggage.fromContext(Context.current())
    def baggageBuilder = Baggage.builder()

    then:
    aBaggage.size() == 0

    when:
    def newBaggage = baggageBuilder.put("key1", "value1")
      .put("key2", "value2", BaggageEntryMetadata.create("some-metadata"))
      .build()

    then:
    aBaggage.size() == 0
    newBaggage.size() == 2

    when:
    newBaggage.storeInContext(Context.current())
    current.close()
    aSpan.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "some-name"
          baggage {
            entry "key1", "value1"
            entry "key2", "value2"
          }
        }
      }
    }
  }
}

import datadog.trace.agent.test.AgentTestRunner
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.BaggageEntryMetadata
import io.opentelemetry.context.Context
import spock.lang.Ignore
import spock.lang.Subject

@Ignore("Feature in development")
class OpenTelemetry1BaggageTest extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("baggage-test")

  def "test baggage storage in span"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def initialBaggage = Baggage.builder().put("key1", "value1")
      .put("key2", "value2", BaggageEntryMetadata.create("some-metadata"))
      .build()
    initialBaggage.makeCurrent()
    def aSpan = builder.startSpan()
    def current = aSpan.makeCurrent()

    //    when:
    //    def aBaggage = Baggage.fromContext(Context.current())
    //    def baggageBuilder = Baggage.builder()

    //    then:
    //    aBaggage.size() == 0

    //    when:
    //    def newBaggage = baggageBuilder.put("key1", "value1")
    //      .put("key2", "value2", BaggageEntryMetadata.create("some-metadata"))
    //      .build()
    //
    //    then:
    //    aBaggage.size() == 0
    //    newBaggage.size() == 2

    when:
    //    newBaggage.makeCurrent()
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

  def "test baggage inheritance from span"() {
    setup:
    def parentSpan = tracer.spanBuilder("some-name").startSpan()
    def parentScope = parentSpan.makeCurrent()

    when:
    def context = Context.current()
    def aBaggage = Baggage.fromContext(context)
    def newBaggage = Baggage.builder().put("key1", "value1")
      .put("key2", "value2")
      .put("key3", "value3")
      .build()

    then:
    aBaggage.size() == 0
    newBaggage.size() == 3

    when:
    newBaggage.storeInContext(Context.current())
    def childSpan = tracer.spanBuilder("some-other-name").startSpan()
    def childScope = childSpan.makeCurrent()
    context = Context.current()
    def childBaggage = Baggage.fromContext(context)
    def newChildBaggage = childBaggage.toBuilder()
      .put("key1", "child-value1")
      //      .remove("key2") // TODO Not supported in DD API
      .put("key4", "value4")
      .build()
    newChildBaggage.storeInContext(context)

    then:
    //    println "+++ child baggage:"
    //    for (final def entry in childBaggage.asMap().entrySet()) {
    //      println "${entry.key} ${entry.value.value}"
    //    }
    //    println "+++ new child baggage:"
    //    for (final def entry in newChildBaggage.asMap().entrySet()) {
    //      println "${entry.key} ${entry.value.value}"
    //    }
    childBaggage.size() == 3
    newChildBaggage.size() == 4 // TODO 3

    when:
    childScope.close()
    childSpan.end()
    parentScope.close()
    parentSpan.end()

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "some-name"
          baggage {
            entry "key1", "value1"
            entry "key2", "value2"
            entry "key3", "value3"
          }
        }
        span(1) {
          operationName"some-other-name"
          baggage {
            entry "key1", "child-value1"
            entry "key2", "value2" // TODO REMOVE
            entry "key3", "value3"
            entry "key4", "value4"
          }
        }
      }
    }
  }

  def "test baggage retrieval"() {
    setup:
    def aSpan = null
    def scope = null
    if (withSpan) {
      aSpan = tracer.spanBuilder("some-name").startSpan()
      scope = aSpan.makeCurrent()
      if (withEntry) {
        Baggage.current()
          .toBuilder()
          .put("key1", "value1")
          .put("key2", "value2")
          .build()
          .storeInContext(Context.current())
      }
    }

    when:
    def currentBaggage = Baggage.current()
    def currentContext = Context.current()
    def baggageFromCurrentContext = Baggage.fromContext(currentContext)
    scope?.close()
    aSpan?.end()

    then:
    currentBaggage == baggageFromCurrentContext
    currentBaggage.size() == (withEntry ? 2 : 0)

    where:
    withSpan | withEntry
    false    | false
    true     | false
    true     | true
  }
}

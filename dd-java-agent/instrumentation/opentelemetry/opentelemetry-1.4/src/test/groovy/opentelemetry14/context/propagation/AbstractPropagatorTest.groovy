package opentelemetry14.context.propagation

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DD128bTraceId
import datadog.trace.api.DDSpanId
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import spock.lang.Subject

import javax.annotation.Nullable

abstract class AbstractPropagatorTest extends AgentTestRunner {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("propagator" + Math.random()) // TODO FIX LATER

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
    injectSysConfig("dd.trace.propagation.style", style())
  }

  /**
   * Gets the propagation style to configure to the agent.
   * @return The propagation style.
   */
  abstract String style()

  /**
   * Gets the propagator to test.
   * @return The propagator to test.
   */
  abstract TextMapPropagator propagator()

  /**
   * Get the test values as an array:
   * <ol>
   *   <li>Headers map</li>
   *   <li>Trace id</li>
   *   <li>Span id</li>
   *   <li>Whether the parent span is sampled</li>
   *  </ol>
   *
   * @return The tests values.
   */
  abstract values()

  /**
   * Evaluates the injected headers of a child span from a continuing trace.
   * @param headers The injected headers.
   * @param traceId The continued trace identifier.
   * @param spanId The child span identifier.
   * @param sampled Whether the child span is sampled.
   */
  abstract void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, boolean sampled)

  def "test context extraction and injection"() {
    setup:
    def propagator = propagator()

    when:
    def context = propagator.extract(Context.root(), headers, new TextMap())

    then:
    context != Context.root()

    when:
    def localSpan = tracer.spanBuilder("some-name")
      .setParent(context)
      .startSpan()
    def localSpanContext = localSpan.getSpanContext()
    def localSpanId = localSpanContext.getSpanId()
    def spanSampled = localSpanContext.getTraceFlags().isSampled()
    def scope = localSpan.makeCurrent()
    Map<String, String> injectedHeaders = [:]
    propagator.inject(Context.current(), injectedHeaders, new TextMap())
    scope.close()
    localSpan.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "some-name"
          traceDDId(DD128bTraceId.fromHex(traceId))
          parentSpanId(DDSpanId.fromHex(spanId).toLong() as BigInteger)
        }
      }
    }
    spanSampled == sampled
    assertInjectedHeaders(injectedHeaders, traceId, localSpanId, sampled)

    where:
    values << values()
    (headers, traceId, spanId, sampled) = values
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }

  static class TextMap implements TextMapGetter<Map<String, String>>, TextMapSetter<Map<String, String>> {
    @Override
    Iterable<String> keys(Map<String, String> carrier) {
      return carrier.keySet()
    }

    @Override
    String get(@Nullable Map<String, String> carrier, String key) {
      return carrier.get(key)
    }

    @Override
    void set(@Nullable Map<String, String> carrier, String key, String value) {
      carrier.put(key, value)
    }
  }
}

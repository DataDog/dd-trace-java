package opentelemetry14.context.propagation

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import io.opentelemetry.context.propagation.TextMapPropagator
import spock.lang.Subject

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP

abstract class AbstractPropagatorTest extends InstrumentationSpecification {
  static int testInstance

  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("propagator" + testInstance++)

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
   * @param sampling The sampling decision.
   */
  abstract void assertInjectedHeaders(Map<String, String> headers, String traceId, String spanId, byte sampling)

  def "test context extraction and injection"() {
    setup:
    def propagator = propagator()
    def expectedSampled = sampling == SAMPLER_KEEP

    when:
    def context = propagator.extract(Context.root(), headers, TextMap.INSTANCE)

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
          operationName "internal"
          resourceName "some-name"
          traceDDId(expectedTraceId(traceId))
          parentSpanId(DDSpanId.fromHex(spanId).toLong() as BigInteger)
        }
      }
    }
    spanSampled == expectedSampled
    assertInjectedHeaders(injectedHeaders, traceId, localSpanId, sampling)

    where:
    values << values()
    (headers, traceId, spanId, sampling) = values
  }

  def expectedTraceId(String traceId) {
    return DDTraceId.fromHex(traceId)
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }
}

package opentelemetry14.context.propagation

import datadog.trace.agent.test.InstrumentationSpecification
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapPropagator

import static io.opentelemetry.context.Context.root

class MissingTraceContextPropagatorTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
  }

  def "extract on missing tracecontext should return an empty context"(TextMapPropagator propagator) {
    setup:
    def headers = ["User-Agent":"test"]

    when:
    def context = propagator.extract(root(), headers, TextMap.INSTANCE)
    def extractedSpan = Span.fromContext(context)

    then: "Should not have a valid tracing context"
    extractedSpan != null
    !extractedSpan.spanContext.valid
    Span.fromContextOrNull(context) == null

    where:
    propagator << [
      GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator(),
      W3CTraceContextPropagator.getInstance()
    ]
  }
}

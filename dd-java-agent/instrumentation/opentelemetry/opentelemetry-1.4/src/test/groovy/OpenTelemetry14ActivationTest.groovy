import datadog.trace.agent.test.InstrumentationSpecification
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context

abstract class OpenTelemetry14ActivationTest extends InstrumentationSpecification {
  abstract boolean shouldBeInjected()

  def "test instrumentation injection"() {
    setup:
    def tracer = GlobalOpenTelemetry.get().tracerProvider.get("some-instrumentation")
    def builder = tracer.spanBuilder("some-name")
    def result = builder.startSpan()
    def context = Context.current()

    expect:
    if (shouldBeInjected()) {
      tracer.class.name.endsWith(".OtelTracer")
      builder.class.name.endsWith(".OtelSpanBuilder")
      result.class.name.endsWith(".OtelSpan")
      context.class.name.endsWith(".OtelContext")
    } else {
      tracer.class.name.endsWith(".DefaultTracer")
      context.class.name.endsWith(".ArrayBasedContext")
    }
  }
}

//
// Below test variants are forked to allow GlobalOpenTelemetry static state to reset
//

class OpenTelemetry14ActivationByInstrumentationNameForkedTest extends OpenTelemetry14ActivationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integration.opentelemetry.experimental.enabled", "true")
  }

  @Override
  boolean shouldBeInjected() {
    return true
  }
}

class OpenTelemetry14ActivationByOtelRfcNameForkedTest extends OpenTelemetry14ActivationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.otel.enabled", "true")
  }

  @Override
  boolean shouldBeInjected() {
    return true
  }
}

class OpenTelemetry14DisableByDefaultForkedTest extends OpenTelemetry14ActivationTest {
  @Override
  boolean shouldBeInjected() {
    return false
  }
}


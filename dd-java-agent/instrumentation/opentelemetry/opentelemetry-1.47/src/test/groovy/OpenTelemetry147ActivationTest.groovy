import datadog.trace.agent.test.InstrumentationSpecification
import io.opentelemetry.api.GlobalOpenTelemetry

abstract class OpenTelemetry147ActivationTest extends InstrumentationSpecification {
  abstract boolean shouldBeInjected()

  def "test instrumentation injection"() {
    setup:
    def meter = GlobalOpenTelemetry.get().meterProvider.get("some-instrumentation")

    expect:
    if (shouldBeInjected()) {
      meter.class.name.endsWith(".OtelMeter")
    } else {
      meter.class.name.endsWith(".DefaultTracer")
    }
  }
}

//
// Below test variants are forked to allow GlobalOpenTelemetry static state to reset
//

class OpenTelemetry147ActivationByInstrumentationNameForkedTest extends OpenTelemetry147ActivationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integration.opentelemetry.metrics.enabled", "true")
  }

  @Override
  boolean shouldBeInjected() {
    return true
  }
}

class OpenTelemetry147ActivationByOtelRfcNameForkedTest extends OpenTelemetry147ActivationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("metrics.otel.enabled", "true")
  }

  @Override
  boolean shouldBeInjected() {
    // TODO: true when OTel metrics fully implemented
    return false
  }
}

class OpenTelemetry147DisableByDefaultForkedTest extends OpenTelemetry147ActivationTest {
  @Override
  boolean shouldBeInjected() {
    return false
  }
}


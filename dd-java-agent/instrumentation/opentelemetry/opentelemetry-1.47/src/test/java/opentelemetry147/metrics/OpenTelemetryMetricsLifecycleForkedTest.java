package opentelemetry147.metrics;

import static datadog.trace.api.metrics.CompletableResultCode.ofSuccess;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.metrics.CompletableResultCode;
import datadog.trace.api.metrics.DatadogMeterProvider;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.Test;

@WithConfig(key = "metrics.otel.enabled", value = "true")
class OpenTelemetryMetricsLifecycleForkedTest extends AbstractInstrumentationTest {

  @Test
  void globalMeterProviderExposesDatadogShutdown() {
    DatadogMeterProvider meterProvider =
        assertInstanceOf(DatadogMeterProvider.class, GlobalOpenTelemetry.get().getMeterProvider());
    Tracer originalTracer = GlobalTracer.get();
    Tracer replacementTracer =
        (Tracer)
            java.lang.reflect.Proxy.newProxyInstance(
                Tracer.class.getClassLoader(),
                new Class<?>[] {Tracer.class},
                (proxy, method, arguments) ->
                    method.getReturnType() == boolean.class ? false : null);

    CompletableResultCode result;
    try {
      GlobalTracer.forceRegister(replacementTracer);
      result = meterProvider.shutdown().join(10, SECONDS);
    } finally {
      GlobalTracer.forceRegister(originalTracer);
    }

    assertNotSame(ofSuccess(), result);
    assertTrue(result.isDone());
  }
}

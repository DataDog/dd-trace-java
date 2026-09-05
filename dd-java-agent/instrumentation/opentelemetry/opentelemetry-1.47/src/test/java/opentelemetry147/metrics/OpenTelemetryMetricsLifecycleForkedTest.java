package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.metrics.CompletableResultCode;
import datadog.trace.api.metrics.DatadogMeterProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

@WithConfig(key = "metrics.otel.enabled", value = "true")
class OpenTelemetryMetricsLifecycleForkedTest extends AbstractInstrumentationTest {

  @Test
  void globalMeterProviderExposesDatadogShutdown() {
    DatadogMeterProvider meterProvider =
        assertInstanceOf(DatadogMeterProvider.class, GlobalOpenTelemetry.get().getMeterProvider());
    AgentTracer.TracerAPI originalAgentTracer = AgentTracer.get();
    Object expected = new CompletableResultCode();
    AgentTracer.TracerAPI replacementAgentTracer =
        (AgentTracer.TracerAPI)
            Proxy.newProxyInstance(
                AgentTracer.TracerAPI.class.getClassLoader(),
                new Class<?>[] {AgentTracer.TracerAPI.class},
                (proxy, method, arguments) ->
                    method.getName().equals("shutdownOtelMetrics") ? expected : null);

    Object result;
    try {
      AgentTracer.forceRegister(replacementAgentTracer);
      result = meterProvider.shutdown();
    } finally {
      AgentTracer.forceRegister(originalAgentTracer);
    }

    assertSame(expected, result);
  }
}

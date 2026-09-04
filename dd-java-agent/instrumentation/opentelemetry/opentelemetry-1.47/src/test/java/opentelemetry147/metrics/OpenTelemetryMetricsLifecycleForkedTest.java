package opentelemetry147.metrics;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
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
    Tracer originalGlobalTracer = GlobalTracer.get();
    AgentTracer.TracerAPI originalAgentTracer = AgentTracer.get();
    Tracer replacementGlobalTracer =
        (Tracer)
            Proxy.newProxyInstance(
                Tracer.class.getClassLoader(),
                new Class<?>[] {Tracer.class},
                (proxy, method, arguments) ->
                    method.getReturnType() == boolean.class ? false : null);
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
      GlobalTracer.forceRegister(replacementGlobalTracer);
      AgentTracer.forceRegister(replacementAgentTracer);
      result = meterProvider.shutdown();
    } finally {
      AgentTracer.forceRegister(originalAgentTracer);
      GlobalTracer.forceRegister(originalGlobalTracer);
    }

    assertSame(expected, result);
  }
}

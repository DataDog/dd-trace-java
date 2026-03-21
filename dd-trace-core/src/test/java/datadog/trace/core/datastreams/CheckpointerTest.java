package datadog.trace.core.datastreams;

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckpointerTest extends DDCoreSpecification {

  @Test
  void testSettingProduceAndConsumeCheckpoint() throws Exception {
    // Enable DSM
    injectSysConfig(DATA_STREAMS_ENABLED, "true");
    // Create a test tracer
    CoreTracer tracer = tracerBuilder().build();
    AgentTracer.forceRegister(tracer);
    // Get the test checkpointer
    DefaultDataStreamsMonitoring checkpointer =
        (DefaultDataStreamsMonitoring) tracer.getDataStreamsCheckpointer();
    // Declare the carrier to test injected data
    CustomContextCarrierCheckpointer carrier = new CustomContextCarrierCheckpointer();
    // Start and activate a span
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer.buildSpan("test", "dsm-checkpoint").start();
    datadog.trace.bootstrap.instrumentation.api.AgentScope scope = tracer.activateSpan(span);

    // Trigger produce checkpoint
    checkpointer.setProduceCheckpoint("kafka", "testTopic", carrier);
    checkpointer.setConsumeCheckpoint("kafka", "testTopic", carrier);
    // Clean up span
    scope.close();
    span.finish();

    assertTrue(
        carrier.entries().stream().anyMatch(e -> "dd-pathway-ctx-base64".equals(e.getKey())));
    assertNotEquals(0, span.context().getPathwayContext().getHash());
  }

  static class CustomContextCarrierCheckpointer implements DataStreamsContextCarrier {

    private Map<String, Object> data = new HashMap<>();

    @Override
    public Set<Map.Entry<String, Object>> entries() {
      return data.entrySet();
    }

    @Override
    public void set(String key, String value) {
      data.put(key, value);
    }
  }
}

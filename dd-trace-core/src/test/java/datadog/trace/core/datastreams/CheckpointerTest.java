package datadog.trace.core.datastreams;

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Enable DSM
@WithConfig(key = DATA_STREAMS_ENABLED, value = "true")
public class CheckpointerTest extends DDCoreJavaSpecification {
  @Test
  void testSettingProduceAndConsumeCheckpoint() {
    // Create a test tracer
    CoreTracer tracer = tracerBuilder().build();
    AgentTracer.forceRegister(tracer);
    // Get the test checkpointer
    DataStreamsCheckpointer checkpointer = tracer.getDataStreamsCheckpointer();
    // Declare the carrier to test injected data
    CustomContextCarrier carrier = new CustomContextCarrier();
    // Start and activate a span
    AgentSpan span = tracer.buildSpan("test", "dsm-checkpoint").start();
    AgentScope scope = tracer.activateSpan(span);

    // Trigger produce checkpoint
    checkpointer.setProduceCheckpoint("kafka", "testTopic", carrier);
    checkpointer.setConsumeCheckpoint("kafka", "testTopic", carrier);
    // Clean up span
    scope.close();
    span.finish();

    boolean hasPathwayCtxBase64 =
        carrier.entries().stream()
            .anyMatch(entry -> "dd-pathway-ctx-base64".equals(entry.getKey()));
    assertTrue(hasPathwayCtxBase64);
    assertNotEquals(0L, ((DDSpan) span).spanContext().getPathwayContext().getHash());
  }

  static class CustomContextCarrier implements DataStreamsContextCarrier {
    private final Map<String, Object> data = new HashMap<>();

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

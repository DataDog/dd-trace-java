import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.aws.v2.sqs.TracingList;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

@WithConfig(key = "sqs.legacy.tracing.enabled", value = "false")
@WithConfig(key = GeneralConfig.DATA_STREAMS_ENABLED, value = "true")
class EventBridgeTimeInQueueForkedTest extends AbstractInstrumentationTest {

  @Test
  void eachEventBridgeMessageInABatchGetsItsOwnTimeInQueueSpan() throws Exception {
    writer.clear();
    String sentTimestamp = String.valueOf(System.currentTimeMillis() - 1000);
    List<Message> messages =
        new TracingList(
            Arrays.asList(
                eventBridgeMessage(4948377316357291421L, 6746998015037429512L, sentTimestamp),
                eventBridgeMessage(1111222233334444L, 5555666677778888L, sentTimestamp)),
            "http://localhost/000000000000/somequeue",
            "00000000-0000-0000-0000-000000000000");

    messages.forEach(ignored -> {});

    writer.waitForTraces(2);
    Map<Long, List<DDSpan>> tracesById = new HashMap<>();
    for (List<DDSpan> trace : writer) {
      tracesById.put(trace.get(0).getTraceId().toLong(), trace);
    }
    assertEquals(2, tracesById.size());
    assertQueueSpanBetweenProducerAndConsumer(
        tracesById.get(4948377316357291421L), 6746998015037429512L);
    assertQueueSpanBetweenProducerAndConsumer(tracesById.get(1111222233334444L), 5555666677778888L);
  }

  private static void assertQueueSpanBetweenProducerAndConsumer(
      List<DDSpan> trace, long producerSpanId) {
    assertNotNull(trace);
    assertEquals(2, trace.size(), () -> "Unexpected trace: " + trace);
    DDSpan queueSpan = spanWithResource(trace, "Sqs.DeliverMessage");
    DDSpan consumerSpan = spanWithResource(trace, "Sqs.ReceiveMessage");
    assertEquals(producerSpanId, queueSpan.getParentId());
    assertEquals(queueSpan.getSpanId(), consumerSpan.getParentId());
  }

  private static DDSpan spanWithResource(List<DDSpan> trace, String resourceName) {
    for (DDSpan span : trace) {
      if (resourceName.contentEquals(span.getResourceName())) {
        return span;
      }
    }
    throw new AssertionError("No " + resourceName + " span in " + trace);
  }

  private static Message eventBridgeMessage(long traceId, long parentId, String sentTimestamp) {
    return Message.builder()
        .body(
            "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":"
                + "{\"x-datadog-trace-id\":\""
                + traceId
                + "\",\"x-datadog-parent-id\":\""
                + parentId
                + "\"}}}")
        .attributesWithStrings(Collections.singletonMap("SentTimestamp", sentTimestamp))
        .build();
  }
}

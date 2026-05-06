package datadog.trace.instrumentation.aws.v2.sns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsInterceptorTest {

  @Test
  void publishBatchPreservesEntriesAndOnlyInjectsBelowTheMessageAttributeLimit() {
    PublishBatchRequest batchRequest =
        PublishBatchRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:test-topic")
            .publishBatchRequestEntries(
                PublishBatchRequestEntry.builder()
                    .id("at-limit")
                    .message("first")
                    .messageAttributes(stringAttributes(10))
                    .build(),
                PublishBatchRequestEntry.builder()
                    .id("under-limit")
                    .message("second")
                    .messageAttributes(stringAttributes(9))
                    .build())
            .build();

    PublishBatchRequest modified =
        (PublishBatchRequest)
            new SnsInterceptor().modifyRequest(() -> batchRequest, executionAttributes());

    assertEquals(
        Arrays.asList("at-limit", "under-limit"),
        modified.publishBatchRequestEntries().stream()
            .map(PublishBatchRequestEntry::id)
            .collect(Collectors.toList()));
    assertFalse(
        modified.publishBatchRequestEntries().get(0).messageAttributes().containsKey("_datadog"));
    assertTrue(
        modified.publishBatchRequestEntries().get(1).messageAttributes().containsKey("_datadog"));
  }

  @Test
  void publishPreservesReadonlyAttributesWhileAddingDatadogContext() {
    Map<String, MessageAttributeValue> headers = new HashMap<>();
    headers.put(
        "mykey", MessageAttributeValue.builder().dataType("String").stringValue("myvalue").build());
    Map<String, MessageAttributeValue> readonlyHeaders = Collections.unmodifiableMap(headers);

    PublishRequest request =
        PublishRequest.builder()
            .topicArn("arn:aws:sns:us-east-1:123456789012:test-topic")
            .message("sometext")
            .messageAttributes(readonlyHeaders)
            .build();

    PublishRequest modified =
        (PublishRequest) new SnsInterceptor().modifyRequest(() -> request, executionAttributes());

    assertNotSame(readonlyHeaders, modified.messageAttributes());
    assertEquals("myvalue", modified.messageAttributes().get("mykey").stringValue());
    assertTrue(modified.messageAttributes().containsKey("_datadog"));
    assertFalse(readonlyHeaders.containsKey("_datadog"));
  }

  private static ExecutionAttributes executionAttributes() {
    ExecutionAttributes executionAttributes = new ExecutionAttributes();
    executionAttributes.putAttribute(SnsInterceptor.CONTEXT_ATTRIBUTE, Context.root());
    return executionAttributes;
  }

  private static Map<String, MessageAttributeValue> stringAttributes(int count) {
    Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
    for (int index = 1; index <= count; index++) {
      attributes.put(
          "key" + index,
          MessageAttributeValue.builder().dataType("String").stringValue("value" + index).build());
    }
    return attributes;
  }
}

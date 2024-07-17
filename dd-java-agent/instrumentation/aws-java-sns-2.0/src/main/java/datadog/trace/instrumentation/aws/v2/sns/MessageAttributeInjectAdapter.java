package datadog.trace.instrumentation.aws.v2.sns;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

public class MessageAttributeInjectAdapter
    implements AgentPropagation.Setter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjectAdapter SETTER = new MessageAttributeInjectAdapter();

  @Override
  public void set(Map<String, MessageAttributeValue> carrier, String key, String value) {
    carrier.put(
        key,
        MessageAttributeValue.builder()
            .dataType("Binary")
            .binaryValue(SdkBytes.fromString(value, StandardCharsets.UTF_8))
            .build());
  }
}

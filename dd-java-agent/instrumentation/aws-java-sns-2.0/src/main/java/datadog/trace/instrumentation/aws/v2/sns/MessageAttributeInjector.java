package datadog.trace.instrumentation.aws.v2.sns;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

public class MessageAttributeInjector
    implements AgentPropagation.Setter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(
      final Map<String, MessageAttributeValue> carrier, final String key, final String value) {
    // 10 messageAttributes is a limit from SQS, which is often used as a subscriber and therefore
    // still apply here
    if (carrier.size() < 10 && !carrier.containsKey(key)) {
      carrier.put(
          key, MessageAttributeValue.builder().dataType("String").stringValue(value).build());
    }
  }
}

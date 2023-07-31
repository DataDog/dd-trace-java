package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.PathwayContext.DSM_KEY;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

public class MessageAttributeInjector
    implements AgentPropagation.Setter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(
      final Map<String, MessageAttributeValue> carrier, final String key, final String value) {
    String jsonPathway = String.format("{\"%s\": \"%s\"}", key, value);
    if (carrier.size() < 10 && !carrier.containsKey(DSM_KEY)) {
      carrier.put(
          DSM_KEY,
          MessageAttributeValue.builder().dataType("String").stringValue(jsonPathway).build());
    }
  }
}

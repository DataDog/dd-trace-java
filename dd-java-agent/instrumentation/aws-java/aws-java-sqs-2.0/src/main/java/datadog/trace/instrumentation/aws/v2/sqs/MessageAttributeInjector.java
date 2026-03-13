package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.api.datastreams.PathwayContext.DATADOG_KEY;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

@ParametersAreNonnullByDefault
public class MessageAttributeInjector implements CarrierSetter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(
      final Map<String, MessageAttributeValue> carrier, final String key, final String value) {
    if (!Config.get().isSqsInjectDatadogAttributeEnabled()) {
      return;
    }
    if (!carrier.containsKey(DATADOG_KEY)) {
      if (carrier.size() >= 10) {
        return;
      }
      carrier.put(
          DATADOG_KEY,
          MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(String.format("{\"%s\": \"%s\"}", key, value))
              .build());
    } else {
      String existing = carrier.get(DATADOG_KEY).stringValue();
      int closingBrace = existing.lastIndexOf('}');
      if (closingBrace >= 0) {
        String updated =
            existing.substring(0, closingBrace) + String.format(", \"%s\": \"%s\"}", key, value);
        carrier.put(
            DATADOG_KEY,
            MessageAttributeValue.builder().dataType("String").stringValue(updated).build());
      }
    }
  }
}

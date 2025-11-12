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
    if (carrier.size() < 10
        && !carrier.containsKey(DATADOG_KEY)
        && Config.get().isAwsInjectDatadogAttributeEnabled()) {

      String jsonPathway = String.format("{\"%s\": \"%s\"}", key, value);
      carrier.put(
          DATADOG_KEY,
          MessageAttributeValue.builder().dataType("String").stringValue(jsonPathway).build());
    }
  }
}

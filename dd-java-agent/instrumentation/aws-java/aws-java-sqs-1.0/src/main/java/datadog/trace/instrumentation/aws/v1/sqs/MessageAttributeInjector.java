package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.api.datastreams.PathwayContext.DATADOG_KEY;

import com.amazonaws.services.sqs.model.MessageAttributeValue;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import java.util.Map;

public class MessageAttributeInjector implements CarrierSetter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(
      final Map<String, MessageAttributeValue> carrier, final String key, final String value) {
    if (carrier.size() < 10
        && !carrier.containsKey(DATADOG_KEY)
        && Config.get().isSqsInjectDatadogAttributeEnabled()) {
      String jsonPathway = String.format("{\"%s\": \"%s\"}", key, value);
      carrier.put(
          DATADOG_KEY,
          new MessageAttributeValue().withDataType("String").withStringValue(jsonPathway));
    }
  }
}

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
    if (!Config.get().isSqsInjectDatadogAttributeEnabled()) {
      return;
    }
    // A single propagator.inject() call invokes set() once per header key (e.g.
    // x-datadog-trace-id, x-datadog-parent-id, dd-pathway-ctx-base64). All of them must be
    // accumulated into the same _datadog JSON attribute rather than overwriting each other.
    if (!carrier.containsKey(DATADOG_KEY)) {
      if (carrier.size() >= 10) {
        return;
      }
      carrier.put(
          DATADOG_KEY,
          new MessageAttributeValue()
              .withDataType("String")
              .withStringValue(String.format("{\"%s\": \"%s\"}", key, value)));
    } else {
      // _datadog was created by an earlier set() call in this same inject session; append to it.
      String existing = carrier.get(DATADOG_KEY).getStringValue();
      if (existing == null) {
        return;
      }
      int closingBrace = existing.lastIndexOf('}');
      if (closingBrace >= 0) {
        String updated =
            existing.substring(0, closingBrace) + String.format(", \"%s\": \"%s\"}", key, value);
        carrier.put(
            DATADOG_KEY,
            new MessageAttributeValue().withDataType("String").withStringValue(updated));
      }
    }
  }
}

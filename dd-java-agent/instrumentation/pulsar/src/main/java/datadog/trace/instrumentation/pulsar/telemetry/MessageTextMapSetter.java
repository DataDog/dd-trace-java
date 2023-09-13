package datadog.trace.instrumentation.pulsar.telemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.pulsar.client.impl.MessageImpl;

public class MessageTextMapSetter implements AgentPropagation.Setter<PulsarRequest>{
  public static final MessageTextMapSetter SETTER = new MessageTextMapSetter();
  @Override
  public void set(PulsarRequest carrier, String key, String value) {
    if (carrier == null) {
      return;
    }
    if (carrier.getMessage() instanceof MessageImpl<?>) {
      MessageImpl<?> message = (MessageImpl<?>) carrier.getMessage();
      message.getMessageBuilder().addProperty().setKey(key).setValue(value);
    }
  }
}

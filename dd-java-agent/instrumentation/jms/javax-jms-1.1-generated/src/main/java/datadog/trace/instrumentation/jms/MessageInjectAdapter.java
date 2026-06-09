package datadog.trace.instrumentation.jms;

import datadog.context.propagation.CarrierSetter;
import javax.jms.JMSException;
import javax.jms.Message;

public class MessageInjectAdapter implements CarrierSetter<Message> {
  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @Override
  public void set(Message message, String key, String value) {
    try {
      // JMS property names must be valid Java identifiers; replace hyphens with underscores
      message.setStringProperty(key.replace('-', '_'), value);
    } catch (JMSException e) {
      // ignore — message may be read-only or property name may be invalid
    }
  }
}

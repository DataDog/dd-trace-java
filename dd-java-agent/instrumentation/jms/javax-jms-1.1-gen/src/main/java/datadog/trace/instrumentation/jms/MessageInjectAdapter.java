package datadog.trace.instrumentation.jms;

import datadog.context.propagation.CarrierSetter;
import javax.jms.JMSException;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageInjectAdapter implements CarrierSetter<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageInjectAdapter.class);

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @Override
  public void set(final Message message, final String key, final String value) {
    try {
      message.setStringProperty(key, value);
    } catch (final JMSException e) {
      if (log.isDebugEnabled()) {
        log.debug("Failed to set JMS property: {}", key, e);
      }
    }
  }
}

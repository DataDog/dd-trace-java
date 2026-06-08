package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.jms.JMSException;
import javax.jms.Message;

public class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message message, AgentPropagation.KeyClassifier classifier) {
    try {
      @SuppressWarnings("unchecked")
      Enumeration<String> propertyNames = message.getPropertyNames();
      while (propertyNames.hasMoreElements()) {
        String name = propertyNames.nextElement();
        String value = message.getStringProperty(name);
        if (value != null) {
          // JMS property names had hyphens replaced with underscores on injection;
          // restore them for propagation header matching
          if (!classifier.accept(name.replace('_', '-'), value)) {
            return;
          }
        }
      }
    } catch (JMSException e) {
      // ignore — message properties may not be accessible
    }
  }
}

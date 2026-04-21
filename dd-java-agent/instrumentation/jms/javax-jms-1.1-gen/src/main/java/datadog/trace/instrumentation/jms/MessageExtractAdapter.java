package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Enumeration;
import javax.jms.JMSException;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageExtractAdapter.class);

  private final Message message;

  public MessageExtractAdapter(final Message message) {
    this.message = message;
  }

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    try {
      Enumeration<?> propertyNames = carrier.getPropertyNames();
      while (propertyNames.hasMoreElements()) {
        String key = (String) propertyNames.nextElement();
        String value = carrier.getStringProperty(key);
        if (value != null && !classifier.accept(key, value)) {
          return;
        }
      }
    } catch (final JMSException e) {
      if (log.isDebugEnabled()) {
        log.debug("Failed to extract JMS properties", e);
      }
    }
  }
}

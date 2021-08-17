package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageInjectAdapter implements AgentPropagation.Setter<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageInjectAdapter.class);

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @SuppressForbidden
  @Override
  public void set(final Message carrier, final String key, final String value) {
    final String propName = key.replace("-", "__dash__");
    try {
      carrier.setStringProperty(propName, value);
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("Failure setting jms property: " + propName, e);
      }
    }
  }
}

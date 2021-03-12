package datadog.trace.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.jms.JMSException;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageInjectAdapter implements AgentPropagation.Setter<Message> {

  private static final Logger log = LoggerFactory.getLogger(MessageInjectAdapter.class);

  private static final boolean USE_LEGACY_DASH_REPLACEMENT =
      Config.get().isJmsLegacyDashReplacement();

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @Override
  @SuppressForbidden
  public void set(final Message carrier, final String key, final String value) {
    final String propName =
        USE_LEGACY_DASH_REPLACEMENT ? key.replace("-", "__dash__") : key.replace('-', '$');
    try {
      carrier.setStringProperty(propName, value);
    } catch (final JMSException e) {
      if (log.isDebugEnabled()) {
        log.debug("Failure setting jms property: " + propName, e);
      }
    }
  }
}

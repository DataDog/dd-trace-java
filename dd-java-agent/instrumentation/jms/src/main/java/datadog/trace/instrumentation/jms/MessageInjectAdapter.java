package datadog.trace.instrumentation.jms;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.jms.JMSException;
import javax.jms.Message;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessageInjectAdapter implements AgentPropagation.Setter<Message> {

  private static final boolean USE_LEGACY_DASH_REPLACEMENT =
      Config.get().isJmsLegacyDashReplacement();

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @Override
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

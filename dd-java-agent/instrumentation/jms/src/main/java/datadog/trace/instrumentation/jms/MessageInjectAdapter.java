package datadog.trace.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.jms.MessageBatchState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageInjectAdapter implements AgentPropagation.Setter<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageInjectAdapter.class);

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  public static final String JMS_PRODUCED_KEY = "x_datadog_jms_produced";
  public static final String JMS_BATCH_ID_KEY = "x_datadog_jms_batch_id";

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

  public void injectTimeInQueue(final Message carrier, final SessionState sessionState) {
    try {
      if (sessionState.isTransactedSession()) {
        MessageBatchState batchState = sessionState.currentBatchState();
        carrier.setLongProperty(JMS_BATCH_ID_KEY, batchState.getBatchId());
        carrier.setLongProperty(JMS_PRODUCED_KEY, batchState.getStartMillis());
      } else {
        carrier.setLongProperty(JMS_PRODUCED_KEY, System.currentTimeMillis());
      }
    } catch (Exception ignored) {
    }
  }
}

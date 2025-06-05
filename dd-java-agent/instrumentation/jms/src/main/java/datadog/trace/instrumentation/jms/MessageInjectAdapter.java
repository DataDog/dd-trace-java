package datadog.trace.instrumentation.jms;

import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_BATCH_ID_KEY;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_PRODUCED_KEY;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.bootstrap.instrumentation.jms.MessageBatchState;
import datadog.trace.bootstrap.instrumentation.jms.MessageProducerState;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageInjectAdapter implements CarrierSetter<Message> {
  private static final Logger log = LoggerFactory.getLogger(MessageInjectAdapter.class);

  public static final MessageInjectAdapter SETTER = new MessageInjectAdapter();

  @ParametersAreNonnullByDefault
  @SuppressForbidden
  @Override
  public void set(final Message carrier, final String key, final String value) {
    final String propName = key.replace("-", "__dash__");
    try {
      carrier.setStringProperty(propName, value);
    } catch (Exception e) {
      log.debug("Failure setting jms property: {}", propName, e);
    }
  }

  public void injectTimeInQueue(final Message carrier, final MessageProducerState producerState) {
    try {
      if (producerState.getSessionState().isTransactedSession()) {
        MessageBatchState batchState = producerState.currentBatchState();
        carrier.setLongProperty(JMS_BATCH_ID_KEY, batchState.getBatchId());
        carrier.setLongProperty(JMS_PRODUCED_KEY, batchState.getStartMillis());
      } else {
        carrier.setLongProperty(JMS_PRODUCED_KEY, System.currentTimeMillis());
      }
    } catch (Exception e) {
      log.debug("Failure setting jms batch details", e);
    }
  }
}

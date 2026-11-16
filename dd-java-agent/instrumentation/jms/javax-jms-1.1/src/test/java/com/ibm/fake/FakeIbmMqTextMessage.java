package com.ibm.fake;

import javax.jms.JMSException;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.Message;

/**
 * ActiveMQ text message whose class name reads as IBM MQ, the only JMS technology with Data Streams
 * checkpoints. Keeps its class across the copies made by the in-VM broker.
 */
public class FakeIbmMqTextMessage extends ActiveMQTextMessage {
  /** Creates a text message with the given body. */
  public FakeIbmMqTextMessage(String text) throws JMSException {
    setText(text);
  }

  @Override
  public Message copy() {
    try {
      FakeIbmMqTextMessage copy = new FakeIbmMqTextMessage(getText());
      copy(copy);
      return copy;
    } catch (JMSException e) {
      throw new IllegalStateException(e);
    }
  }
}

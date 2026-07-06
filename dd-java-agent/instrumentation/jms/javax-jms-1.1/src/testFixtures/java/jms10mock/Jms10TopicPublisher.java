package jms10mock;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Topic;
import javax.jms.TopicPublisher;

/** Wraps a real {@link MessageProducer} but simulates a JMS 1.0 provider. */
public class Jms10TopicPublisher implements TopicPublisher {
  private final MessageProducer delegate;
  private final Topic topic;

  public Jms10TopicPublisher(MessageProducer delegate, Topic topic) {
    this.delegate = delegate;
    this.topic = topic;
  }

  // --- JMS 1.1-only methods — not present in JMS 1.0 ---

  @Override
  public Destination getDestination() {
    throw new AbstractMethodError("JMS 1.0 provider does not implement getDestination()");
  }

  @Override
  public void send(Destination destination, Message message) throws JMSException {
    delegate.send(destination, message);
  }

  @Override
  public void send(
      Destination destination, Message message, int deliveryMode, int priority, long timeToLive)
      throws JMSException {
    delegate.send(destination, message, deliveryMode, priority, timeToLive);
  }

  // --- JMS 1.0 TopicPublisher methods ---

  @Override
  public Topic getTopic() {
    return topic;
  }

  @Override
  public void publish(Message message) throws JMSException {
    delegate.send(message);
  }

  @Override
  public void publish(Message message, int deliveryMode, int priority, long timeToLive)
      throws JMSException {
    delegate.send(message, deliveryMode, priority, timeToLive);
  }

  @Override
  public void publish(Topic topic, Message message) throws JMSException {
    delegate.send(topic, message);
  }

  @Override
  public void publish(Topic topic, Message message, int deliveryMode, int priority, long timeToLive)
      throws JMSException {
    delegate.send(topic, message, deliveryMode, priority, timeToLive);
  }

  // --- MessageProducer send methods (also available via publish in 1.0) ---

  @Override
  public void send(Message message) throws JMSException {
    delegate.send(message);
  }

  @Override
  public void send(Message message, int deliveryMode, int priority, long timeToLive)
      throws JMSException {
    delegate.send(message, deliveryMode, priority, timeToLive);
  }

  // --- MessageProducer config methods ---

  @Override
  public void close() throws JMSException {
    delegate.close();
  }

  @Override
  public void setDisableMessageID(boolean value) throws JMSException {
    delegate.setDisableMessageID(value);
  }

  @Override
  public boolean getDisableMessageID() throws JMSException {
    return delegate.getDisableMessageID();
  }

  @Override
  public void setDisableMessageTimestamp(boolean value) throws JMSException {
    delegate.setDisableMessageTimestamp(value);
  }

  @Override
  public boolean getDisableMessageTimestamp() throws JMSException {
    return delegate.getDisableMessageTimestamp();
  }

  @Override
  public void setDeliveryMode(int deliveryMode) throws JMSException {
    delegate.setDeliveryMode(deliveryMode);
  }

  @Override
  public int getDeliveryMode() throws JMSException {
    return delegate.getDeliveryMode();
  }

  @Override
  public void setPriority(int defaultPriority) throws JMSException {
    delegate.setPriority(defaultPriority);
  }

  @Override
  public int getPriority() throws JMSException {
    return delegate.getPriority();
  }

  @Override
  public void setTimeToLive(long timeToLive) throws JMSException {
    delegate.setTimeToLive(timeToLive);
  }

  @Override
  public long getTimeToLive() throws JMSException {
    return delegate.getTimeToLive();
  }
}

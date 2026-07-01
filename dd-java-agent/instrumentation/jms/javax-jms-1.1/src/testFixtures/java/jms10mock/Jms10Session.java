package jms10mock;

import java.io.Serializable;
import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;

/** Wraps a real {@link Session} but simulates a JMS 1.0 provider. */
public class Jms10Session implements QueueSession, TopicSession {
  private final Session delegate;

  public Jms10Session(Session delegate) {
    this.delegate = delegate;
  }

  // --- JMS 1.1-only unified Session methods — not present in JMS 1.0 ---

  @Override
  public MessageProducer createProducer(Destination destination) throws JMSException {
    return delegate.createProducer(destination);
  }

  @Override
  public MessageConsumer createConsumer(Destination destination) throws JMSException {
    return delegate.createConsumer(destination);
  }

  @Override
  public MessageConsumer createConsumer(Destination destination, String messageSelector)
      throws JMSException {
    return delegate.createConsumer(destination, messageSelector);
  }

  @Override
  public MessageConsumer createConsumer(
      Destination destination, String messageSelector, boolean noLocal) throws JMSException {
    return delegate.createConsumer(destination, messageSelector, noLocal);
  }

  // --- JMS 1.0 QueueSession methods ---

  @Override
  public Queue createQueue(String queueName) throws JMSException {
    return delegate.createQueue(queueName);
  }

  @Override
  public QueueReceiver createReceiver(Queue queue) throws JMSException {
    return new Jms10QueueReceiver(delegate.createConsumer(queue), queue);
  }

  @Override
  public QueueReceiver createReceiver(Queue queue, String messageSelector) throws JMSException {
    return new Jms10QueueReceiver(delegate.createConsumer(queue, messageSelector), queue);
  }

  @Override
  public QueueSender createSender(Queue queue) throws JMSException {
    return new Jms10QueueSender(delegate.createProducer(queue), queue);
  }

  @Override
  public QueueBrowser createBrowser(Queue queue) throws JMSException {
    return delegate.createBrowser(queue);
  }

  @Override
  public QueueBrowser createBrowser(Queue queue, String messageSelector) throws JMSException {
    return delegate.createBrowser(queue, messageSelector);
  }

  @Override
  public TemporaryQueue createTemporaryQueue() throws JMSException {
    return delegate.createTemporaryQueue();
  }

  // --- JMS 1.0 TopicSession methods ---

  @Override
  public Topic createTopic(String topicName) throws JMSException {
    return delegate.createTopic(topicName);
  }

  @Override
  public TopicSubscriber createSubscriber(Topic topic) throws JMSException {
    return new Jms10TopicSubscriber(delegate.createConsumer(topic), topic, false);
  }

  @Override
  public TopicSubscriber createSubscriber(Topic topic, String messageSelector, boolean noLocal)
      throws JMSException {
    return new Jms10TopicSubscriber(
        delegate.createConsumer(topic, messageSelector, noLocal), topic, noLocal);
  }

  @Override
  public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException {
    return new Jms10TopicSubscriber(delegate.createDurableSubscriber(topic, name), topic, false);
  }

  @Override
  public TopicSubscriber createDurableSubscriber(
      Topic topic, String name, String messageSelector, boolean noLocal) throws JMSException {
    return new Jms10TopicSubscriber(
        delegate.createDurableSubscriber(topic, name, messageSelector, noLocal), topic, noLocal);
  }

  @Override
  public TopicPublisher createPublisher(Topic topic) throws JMSException {
    return new Jms10TopicPublisher(delegate.createProducer(topic), topic);
  }

  @Override
  public TemporaryTopic createTemporaryTopic() throws JMSException {
    return delegate.createTemporaryTopic();
  }

  @Override
  public void unsubscribe(String name) throws JMSException {
    delegate.unsubscribe(name);
  }

  // --- Common Session methods ---

  @Override
  public BytesMessage createBytesMessage() throws JMSException {
    return delegate.createBytesMessage();
  }

  @Override
  public MapMessage createMapMessage() throws JMSException {
    return delegate.createMapMessage();
  }

  @Override
  public Message createMessage() throws JMSException {
    return delegate.createMessage();
  }

  @Override
  public ObjectMessage createObjectMessage() throws JMSException {
    return delegate.createObjectMessage();
  }

  @Override
  public ObjectMessage createObjectMessage(Serializable object) throws JMSException {
    return delegate.createObjectMessage(object);
  }

  @Override
  public StreamMessage createStreamMessage() throws JMSException {
    return delegate.createStreamMessage();
  }

  @Override
  public TextMessage createTextMessage() throws JMSException {
    return delegate.createTextMessage();
  }

  @Override
  public TextMessage createTextMessage(String text) throws JMSException {
    return delegate.createTextMessage(text);
  }

  @Override
  public boolean getTransacted() throws JMSException {
    return delegate.getTransacted();
  }

  @Override
  public int getAcknowledgeMode() {
    throw new AbstractMethodError("JMS 1.0 provider does not implement getAcknowledgeMode()");
  }

  @Override
  public void commit() throws JMSException {
    delegate.commit();
  }

  @Override
  public void rollback() throws JMSException {
    delegate.rollback();
  }

  @Override
  public void close() throws JMSException {
    delegate.close();
  }

  @Override
  public void recover() throws JMSException {
    delegate.recover();
  }

  @Override
  public MessageListener getMessageListener() throws JMSException {
    return delegate.getMessageListener();
  }

  @Override
  public void setMessageListener(MessageListener listener) throws JMSException {
    delegate.setMessageListener(listener);
  }

  @Override
  public void run() {
    delegate.run();
  }
}

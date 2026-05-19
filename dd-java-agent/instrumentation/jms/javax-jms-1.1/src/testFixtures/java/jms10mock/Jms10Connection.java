package jms10mock;

import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueSession;
import javax.jms.ServerSessionPool;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicSession;

/** Wraps a real {@link Connection} but simulates a JMS 1.0 provider. */
public class Jms10Connection implements QueueConnection, TopicConnection {
  private final Connection delegate;

  public Jms10Connection(Connection delegate) {
    this.delegate = delegate;
  }

  // --- JMS 1.1-only unified Connection method ---

  @Override
  public Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
    throw new AbstractMethodError(
        "JMS 1.0 provider does not implement createSession(boolean, int) on Connection");
  }

  // --- JMS 1.0 QueueConnection methods ---

  @Override
  public QueueSession createQueueSession(boolean transacted, int acknowledgeMode)
      throws JMSException {
    return new Jms10Session(delegate.createSession(transacted, acknowledgeMode));
  }

  // --- JMS 1.0 TopicConnection methods ---

  @Override
  public TopicSession createTopicSession(boolean transacted, int acknowledgeMode)
      throws JMSException {
    return new Jms10Session(delegate.createSession(transacted, acknowledgeMode));
  }

  // --- Common Connection methods ---

  @Override
  public String getClientID() throws JMSException {
    return delegate.getClientID();
  }

  @Override
  public void setClientID(String clientID) throws JMSException {
    delegate.setClientID(clientID);
  }

  @Override
  public ConnectionMetaData getMetaData() throws JMSException {
    return delegate.getMetaData();
  }

  @Override
  public ExceptionListener getExceptionListener() throws JMSException {
    return delegate.getExceptionListener();
  }

  @Override
  public void setExceptionListener(ExceptionListener listener) throws JMSException {
    delegate.setExceptionListener(listener);
  }

  @Override
  public void start() throws JMSException {
    delegate.start();
  }

  @Override
  public void stop() throws JMSException {
    delegate.stop();
  }

  @Override
  public void close() throws JMSException {
    delegate.close();
  }

  // --- ConnectionConsumer methods — not commonly used, throw for JMS 1.1 unified form ---

  @Override
  public ConnectionConsumer createConnectionConsumer(
      Destination destination,
      String messageSelector,
      ServerSessionPool sessionPool,
      int maxMessages)
      throws JMSException {
    throw new AbstractMethodError(
        "JMS 1.0 provider does not implement createConnectionConsumer(Destination, ...)");
  }

  @Override
  public ConnectionConsumer createConnectionConsumer(
      Queue queue, String messageSelector, ServerSessionPool sessionPool, int maxMessages)
      throws JMSException {
    return delegate.createConnectionConsumer(queue, messageSelector, sessionPool, maxMessages);
  }

  @Override
  public ConnectionConsumer createConnectionConsumer(
      Topic topic, String messageSelector, ServerSessionPool sessionPool, int maxMessages)
      throws JMSException {
    return delegate.createConnectionConsumer(topic, messageSelector, sessionPool, maxMessages);
  }

  @Override
  public ConnectionConsumer createDurableConnectionConsumer(
      Topic topic,
      String subscriptionName,
      String messageSelector,
      ServerSessionPool sessionPool,
      int maxMessages)
      throws JMSException {
    return delegate.createDurableConnectionConsumer(
        topic, subscriptionName, messageSelector, sessionPool, maxMessages);
  }
}

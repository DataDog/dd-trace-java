package jms10mock;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;

/**
 * Wraps a real {@link ConnectionFactory} but simulates a JMS 1.0 provider.
 *
 * <p>In JMS 1.0, clients used the domain-specific {@link QueueConnectionFactory} and {@link
 * TopicConnectionFactory} to obtain connections. The unified {@link ConnectionFactory} and its
 * {@code createConnection()} methods are JMS 1.1 additions that this wrapper does not support.
 */
public class Jms10ConnectionFactory implements QueueConnectionFactory, TopicConnectionFactory {
  private final ConnectionFactory delegate;

  public Jms10ConnectionFactory(ConnectionFactory delegate) {
    this.delegate = delegate;
  }

  // --- JMS 1.1-only unified ConnectionFactory methods ---

  @Override
  public Connection createConnection() throws JMSException {
    return delegate.createConnection();
  }

  @Override
  public Connection createConnection(String userName, String password) throws JMSException {
    return delegate.createConnection(userName, password);
  }

  // --- JMS 1.0 QueueConnectionFactory methods ---
  @Override
  public QueueConnection createQueueConnection() throws JMSException {
    return new Jms10Connection(delegate.createConnection());
  }

  @Override
  public QueueConnection createQueueConnection(String userName, String password)
      throws JMSException {
    return new Jms10Connection(delegate.createConnection(userName, password));
  }

  // --- JMS 1.0 TopicConnectionFactory methods ---

  @Override
  public TopicConnection createTopicConnection() throws JMSException {
    return new Jms10Connection(delegate.createConnection());
  }

  @Override
  public TopicConnection createTopicConnection(String userName, String password)
      throws JMSException {
    return new Jms10Connection(delegate.createConnection(userName, password));
  }
}

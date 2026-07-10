package jms10mock;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;

/** Wraps a real {@link MessageConsumer} but simulates a JMS 1.0 provider. */
public class Jms10TopicSubscriber implements TopicSubscriber {
  private final MessageConsumer delegate;
  private final Topic topic;
  private final boolean noLocal;

  public Jms10TopicSubscriber(MessageConsumer delegate, Topic topic, boolean noLocal) {
    this.delegate = delegate;
    this.topic = topic;
    this.noLocal = noLocal;
  }

  @Override
  public Topic getTopic() {
    return topic;
  }

  @Override
  public boolean getNoLocal() {
    return noLocal;
  }

  @Override
  public String getMessageSelector() throws JMSException {
    return delegate.getMessageSelector();
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
  public Message receive() throws JMSException {
    return delegate.receive();
  }

  @Override
  public Message receive(long timeout) throws JMSException {
    return delegate.receive(timeout);
  }

  @Override
  public Message receiveNoWait() throws JMSException {
    return delegate.receiveNoWait();
  }

  @Override
  public void close() throws JMSException {
    delegate.close();
  }
}

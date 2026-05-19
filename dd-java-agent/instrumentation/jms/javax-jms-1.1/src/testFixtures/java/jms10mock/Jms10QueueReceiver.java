package jms10mock;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueReceiver;

/** Wraps a real {@link MessageConsumer} but simulates a JMS 1.0 provider. */
public class Jms10QueueReceiver implements QueueReceiver {
  private final MessageConsumer delegate;
  private final Queue queue;

  public Jms10QueueReceiver(MessageConsumer delegate, Queue queue) {
    this.delegate = delegate;
    this.queue = queue;
  }

  @Override
  public Queue getQueue() {
    return queue;
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

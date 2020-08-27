package salistener

import javax.jms.JMSException
import javax.jms.Message
import javax.jms.Session
import org.springframework.jms.listener.SessionAwareMessageListener

class SATestListener<M extends Message> implements SessionAwareMessageListener<M> {

  @Override
  void onMessage(M message, Session session) throws JMSException {
    println "sa received: " + message
  }
}

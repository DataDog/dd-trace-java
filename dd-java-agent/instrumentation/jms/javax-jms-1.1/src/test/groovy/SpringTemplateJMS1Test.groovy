import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.test.util.Flaky
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.junit.EmbeddedActiveMQBroker
import org.springframework.jms.core.JmsTemplate
import spock.lang.Shared

import javax.jms.Connection
import javax.jms.Session
import javax.jms.TextMessage
import java.util.concurrent.TimeUnit

import static JMS1Test.consumerTrace
import static JMS1Test.producerTrace

class SpringTemplateJMS1Test extends InstrumentationSpecification {
  @Shared
  EmbeddedActiveMQBroker broker = new EmbeddedActiveMQBroker()
  @Shared
  JmsTemplate template
  @Shared
  Session session

  def setupSpec() {
    broker.start()
    final ActiveMQConnectionFactory connectionFactory = broker.createConnectionFactory()
    final Connection connection = connectionFactory.createConnection()
    connection.start()
    session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)

    template = new JmsTemplate(connectionFactory)
    // Make this longer than timeout on TEST_WRITER.waitForTraces
    // Otherwise caller might give up waiting before callee has a chance to respond.
    template.receiveTimeout = TimeUnit.SECONDS.toMillis(21)
  }

  def cleanupSpec() {
    broker.stop()
  }

  def "sending a message to #jmsResourceName generates spans"() {
    setup:
    template.convertAndSend(destination, messageText)
    TextMessage receivedMessage = template.receive(destination)

    expect:
    receivedMessage.text == messageText
    assertTraces(2) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
    }

    where:
    destination                               | jmsResourceName
    session.createQueue("SpringTemplateJMS1") | "Queue SpringTemplateJMS1"

    messageText = "a message"
  }

  @Flaky("Sometimes fails when finding errors in traces: Cannot publish to a deleted Destination: temp-queue://...")
  def "send and receive message generates spans"() {
    setup:
    Thread.start {
      TextMessage msg = template.receive(destination)
      assert msg.text == messageText

      template.send(msg.getJMSReplyTo()) { session ->
        template.getMessageConverter().toMessage("responded!", session)
      }
    }
    TextMessage receivedMessage = template.sendAndReceive(destination) { session ->
      template.getMessageConverter().toMessage(messageText, session)
    }

    expect:
    receivedMessage.text == "responded!"
    assertTraces(4) {
      producerTrace(it, jmsResourceName)
      consumerTrace(it, jmsResourceName, trace(0)[0])
      producerTrace(it, "Temporary Queue") // receive doesn't propagate the trace, so this is a root
      consumerTrace(it, "Temporary Queue", trace(2)[0])
    }

    where:
    destination                               | jmsResourceName
    session.createQueue("SpringTemplateJMS1") | "Queue SpringTemplateJMS1"

    messageText = "a message"
  }
}

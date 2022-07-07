import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import org.testcontainers.containers.RabbitMQContainer
import rabbit.MessagingRabbitMQApplication
import rabbit.Receiver
import rabbit.Sender
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SpringAmqpTest extends AgentTestRunner {

  @Shared
  RabbitMQContainer rabbit

  @Override
  def setupSpec() {
    rabbit = new RabbitMQContainer("rabbitmq:3.9.20-alpine")
    rabbit.start()
    def hostName = rabbit.getHost()
    def port = rabbit.getMappedPort(MessagingRabbitMQApplication.port)
    MessagingRabbitMQApplication.hostName = hostName
    MessagingRabbitMQApplication.port = port
    PortUtils.waitForPortToOpen(hostName, port, 5, TimeUnit.SECONDS)
  }

  @Override
  def cleanupSpec() {
    if (null != rabbit) {
      rabbit.close()
    }
  }

  def "test propagation to receiver"() {
    setup:
    def application = MessagingRabbitMQApplication.run()
    def sender = application.getBean(Sender)
    def receiver = application.getBean(Receiver)
    // various setup actions are traced
    TEST_WRITER.waitForTraces(7)
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      sender.send("foo", "hello")
    }
    then: "context propagated to the receiver"
    assert receiver.latch.await(5, TimeUnit.SECONDS)

    assertTraces(3) {
      trace(2, true) {
        span(0) {
          childOf(span(1))
          operationName "amqp.command"
          resourceName "basic.publish test-exchange -> foo.bar.foo"
          spanType "queue"
        }
        span(1) {
          operationName "parent"
        }
      }
      trace(3, true) {
        span(0) {
          operationName "amqp.command"
          resourceName "basic.deliver test-queue"
          spanType "queue"
        }
        span(1) {
          childOf(span(0))
          operationName "amqp.consume"
          resourceName "amqp.consume test-queue"
        }
        span(2) {
          childOf(span(1))
          operationName "receive"
          resourceName "Receiver.receiveMessage"
        }
      }
      trace(1) {
        span(0) {
          operationName "amqp.command"
          resourceName "basic.ack"
          spanType "queue"
        }
      }
    }

    cleanup:
    application.close()
  }
}

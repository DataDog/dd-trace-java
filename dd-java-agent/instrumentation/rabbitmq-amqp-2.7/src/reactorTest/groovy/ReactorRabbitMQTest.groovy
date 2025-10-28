import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.PortUtils
import org.testcontainers.containers.RabbitMQContainer
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.rabbitmq.RabbitFlux
import reactor.rabbitmq.Sender
import reactor.rabbitmq.SenderOptions
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.TimeUnit

class ReactorRabbitMQTest extends InstrumentationSpecification {
  @Shared
  def rabbitMQContainer
  @Shared
  def defaultRabbitMQPort = 5672
  @Shared
  InetSocketAddress rabbitmqAddress = new InetSocketAddress("127.0.0.1", defaultRabbitMQPort)

  ConnectionFactory factory

  def setup() {
    factory = new ConnectionFactory(host: rabbitmqAddress.hostName, port: rabbitmqAddress.port)
  }

  def setupSpec() {
    rabbitMQContainer = new RabbitMQContainer('rabbitmq:3.9.20-alpine')
      .withExposedPorts(defaultRabbitMQPort)
      .withStartupTimeout(Duration.ofSeconds(120))
    rabbitMQContainer.start()
    rabbitmqAddress = new InetSocketAddress(
      rabbitMQContainer.getHost(),
      rabbitMQContainer.getMappedPort(defaultRabbitMQPort)
      )
    PortUtils.waitForPortToOpen(rabbitmqAddress.hostString, rabbitmqAddress.port, 5, TimeUnit.SECONDS)
  }

  def cleanupSpec() {
    if (rabbitMQContainer) {
      rabbitMQContainer.stop()
    }
  }

  def "test reactor-rabbit does not cause exceptions from calling channel.asyncCompletableRpc"() {
    setup:
    SenderOptions senderOptions =  new SenderOptions()
      .connectionFactory(factory)
      .resourceManagementScheduler(Schedulers.elastic())
    Sender sender = RabbitFlux.createSender(senderOptions)

    Mono<Channel> channelMono = sender.getChannelMonoForResourceManagement(null)
    Channel channel1 = channelMono.block()

    expect:
    channel1.getClass().getCanonicalName() == "reactor.rabbitmq.ChannelProxy"

    when:
    channel1.asyncCompletableRpc(new AMQP.Connection.Close.Builder().build())

    then:
    noExceptionThrown()
  }
}

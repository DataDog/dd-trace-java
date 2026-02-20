import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.ShutdownSignalException
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.testcontainers.containers.RabbitMQContainer
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.time.Duration
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES

abstract class RabbitMQTestBase extends VersionedNamingTestBase {
  @Shared
  def rabbitMQContainer
  @Shared
  def defaultRabbitMQPort = 5672
  @Shared
  InetSocketAddress rabbitmqAddress = new InetSocketAddress("127.0.0.1", defaultRabbitMQPort)

  def factory
  def conn
  def channel

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.amqp.e2e.duration.enabled", "true")
  }

  @Override
  boolean isDataStreamsEnabled() {
    true
  }

  def setup() {
    factory = new ConnectionFactory(host: rabbitmqAddress.hostName, port: rabbitmqAddress.port)
    conn = factory.newConnection()
    channel = conn.createChannel()
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

  def cleanup() {
    try {
      channel?.close()
      conn?.close()
      TEST_DATA_STREAMS_WRITER?.clear()
    } catch (ShutdownSignalException e) {
      // Ignore
    }
  }

  abstract boolean hasQueueSpan()

  abstract boolean splitByDestination()

  boolean excludesRoutingKeyFromResource() {
    false
  }

  String operationForProducer() {
    "amqp.command"
  }

  String operationForConsumer() {
    "amqp.command"
  }

  String serviceForTimeInQueue() {
    "rabbitmq"
  }


  @Override
  int version() {
    return 0
  }


  @Override
  String operation() {
    return "amqp.command"
  }

  def "test rabbit publish/get"() {
    setup:
    String queueName
    GetResponse response = runUnderTrace("parent") {
      channel.exchangeDeclare(exchangeName, "direct", false)
      queueName = channel.queueDeclare().getQueue()
      channel.queueBind(queueName, exchangeName, routingKey)
      channel.basicPublish(exchangeName, routingKey, null, "Hello, world!".getBytes())
      return channel.basicGet(queueName, true)
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    expect:
    new String(response.getBody()) == "Hello, world!"

    and:
    assertTraces(2, SORT_TRACES_BY_ID) {
      def publishSpan = null
      trace(5) {
        publishSpan = span(1)
        def parentSpan = span(0)
        basicSpan(it, "parent")
        rabbitSpan(it, "basic.publish $exchangeName -> $routingKey", false, parentSpan, operationForProducer())
        rabbitSpan(it, "queue.bind", false, parentSpan)
        rabbitSpan(it, "queue.declare", false, parentSpan)
        rabbitSpan(it, "exchange.declare", false, parentSpan)
      }
      if (hasQueueSpan()) {
        trace(2) {
          rabbitSpan(it, "basic.get <generated>", false, span(1), operationForConsumer())
          rabbitQueueSpan(it, "amqp.deliver <generated>", true, publishSpan)
        }
      } else {
        trace(1) {
          rabbitSpan(it, "basic.get <generated>", true, publishSpan, operationForConsumer())
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "exchange:" + exchangeName, "has_routing_key:true", "type:rabbitmq")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
      }
    }

    where:
    exchangeName    | routingKey
    "some-exchange" | "some-routing-key"
  }

  def "test rabbit publish/get default exchange"() {
    setup:
    String queueName = channel.queueDeclare().getQueue()
    channel.basicPublish("", queueName, null, "Hello, world!".getBytes())
    GetResponse response = channel.basicGet(queueName, true)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    expect:
    new String(response.getBody()) == "Hello, world!"

    and:
    assertTraces(3, SORT_TRACES_BY_ID) {
      def publishSpan = null
      trace(1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(1) {
        publishSpan = span(0)
        rabbitSpan(it, "basic.publish <default> -> <generated>", false, null, operationForProducer())
      }
      if (hasQueueSpan()) {
        trace(2) {
          rabbitSpan(it, "basic.get <generated>", false, span(1), operationForConsumer())
          rabbitQueueSpan(it, "amqp.deliver <generated>", true, publishSpan)
        }
      } else {
        trace(1) {
          rabbitSpan(it, "basic.get <generated>", true, publishSpan, operationForConsumer())
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "exchange:", "has_routing_key:true", "type:rabbitmq")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
      }
    }
  }

  def "test rabbit consume #messageCount messages"() {
    setup:
    channel.exchangeDeclare(exchangeName, "direct", false)
    String queueName = (messageCount % 2 == 0) ?
      channel.queueDeclare().getQueue() :
      channel.queueDeclare("some-queue", false, true, true, null).getQueue()
    channel.queueBind(queueName, exchangeName, "")

    def phaser = new Phaser()
    phaser.register()
    phaser.register()
    def deliveries = []

    Consumer callback = new DefaultConsumer(channel) {
        @Override
        void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
          phaser.arriveAndAwaitAdvance() // Ensure publish spans are reported first.
          deliveries << new String(body)
        }
      }

    channel.basicConsume(queueName, callback)

    (1..messageCount).each {
      TEST_WRITER.waitForTraces(4 + ((it - 1) * 2))
      if (setTimestamp) {
        channel.basicPublish(exchangeName, "",
          new AMQP.BasicProperties.Builder().timestamp(new Date()).build(),
          "msg $it".getBytes())
      } else {
        channel.basicPublish(exchangeName, "", null, "msg $it".getBytes())
      }
      TEST_WRITER.waitForTraces(5 + ((it - 1) * 2))
      if (isDataStreamsEnabled()) {
        TEST_DATA_STREAMS_WRITER.waitForGroups(it * 2)
      }
      phaser.arriveAndAwaitAdvance()
    }
    def resourceQueueName = messageCount % 2 == 0 ? "<generated>" : queueName

    expect:
    assertTraces(4 + (messageCount * 2), SORT_TRACES_BY_ID) {
      trace(1) {
        rabbitSpan(it, "exchange.declare")
      }
      trace(1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(1) {
        rabbitSpan(it, "queue.bind")
      }
      trace(1) {
        rabbitSpan(it, "basic.consume")
      }
      (1..messageCount).each {
        def deliverParentSpan = null
        trace(1) {
          deliverParentSpan = span(0)
          rabbitSpan(it, "basic.publish $exchangeName -> <all>", false, null, operationForProducer())
        }
        if (hasQueueSpan()) {
          trace(2) {
            rabbitSpan(it, "basic.deliver $resourceQueueName", false, span(1), operationForConsumer(),
              null, null, setTimestamp)
            rabbitQueueSpan(it, "amqp.deliver $resourceQueueName", true, deliverParentSpan)
          }
        } else {
          trace(1) {
            // TODO - test with and without feature enabled once Config is easier to control
            rabbitSpan(it, "basic.deliver $resourceQueueName", true, deliverParentSpan,
              operationForConsumer(), null, null, setTimestamp)
          }
        }
      }
    }

    deliveries == (1..messageCount).collect { "msg $it" }

    and:
    if (isDataStreamsEnabled()) {
      // Look for all StatsPoints created by the producer. They will have the same hash, which would be the consumer
      // points' parent hash.
      List<StatsGroup> producerPoints = TEST_DATA_STREAMS_WRITER.groups.findAll { it.parentHash == 0 }
      producerPoints.each { producerPoint ->
        verifyAll(producerPoint) {
          tags.hasAllTags("direction:out", "exchange:" + exchangeName, "has_routing_key:false", "type:rabbitmq")
        }
      }

      StatsGroup consumerPoint = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == producerPoints.get(0).hash }
      verifyAll(consumerPoint) {
        tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
      }
    }

    where:
    exchangeName    | messageCount | setTimestamp
    "some-exchange" | 1            | false
    "some-exchange" | 2            | false
    "some-exchange" | 3            | false
    "some-exchange" | 4            | false
    "some-exchange" | 1            | true
    "some-exchange" | 2            | true
    "some-exchange" | 3            | true
    "some-exchange" | 4            | true
  }

  def "test rabbit consume error"() {
    setup:
    def error = new FileNotFoundException("Message Error")
    channel.exchangeDeclare(exchangeName, "direct", false)
    String queueName = channel.queueDeclare().getQueue()
    channel.queueBind(queueName, exchangeName, "")

    def phaser = new Phaser()
    phaser.register()
    phaser.register()

    Consumer callback = new DefaultConsumer(channel) {
        @Override
        void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
          phaser.arriveAndAwaitAdvance() // Ensure publish spans are reported first.
          throw error
          // Unfortunately this doesn't seem to be observable in the test outside of the span generated.
        }
      }

    channel.basicConsume(queueName, callback)

    TEST_WRITER.waitForTraces(2)
    channel.basicPublish(exchangeName, "", null, "msg".getBytes())
    TEST_WRITER.waitForTraces(3)
    phaser.arriveAndAwaitAdvance()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    expect:
    assertTraces(6, SORT_TRACES_BY_ID) {
      trace(1) {
        rabbitSpan(it, "exchange.declare")
      }
      trace(1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(1) {
        rabbitSpan(it, "queue.bind")
      }
      trace(1) {
        rabbitSpan(it, "basic.consume")
      }
      def deliverParentSpan = null
      trace(1) {
        deliverParentSpan = span(0)
        rabbitSpan(it, "basic.publish $exchangeName -> <all>", false, null, operationForProducer())
      }
      if (hasQueueSpan()) {
        trace(2) {
          rabbitSpan(it, "basic.deliver <generated>", false, span(1), operationForConsumer(),
            error, error.message, false)
          rabbitQueueSpan(it, "amqp.deliver <generated>", true, deliverParentSpan)
        }
      } else {
        trace(1) {
          // TODO - test with and without feature enabled once Config is easier to control
          rabbitSpan(it, "basic.deliver <generated>", true, deliverParentSpan, operationForConsumer(),
            error, error.message, false)
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "exchange:" + exchangeName, "has_routing_key:false", "type:rabbitmq")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
      }
    }

    where:
    exchangeName = "some-error-exchange"
  }

  def "test rabbit error (#command)"() {
    when:
    closure.call(channel)

    then:
    def throwable = thrown(exception)

    and:

    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(1) {
        rabbitSpan(it, command, false, null, exptectedOperation, throwable, errorMsg)
      }
    }

    where:
    command                 | exception             | exptectedOperation     | errorMsg                                           | closure
    "exchange.declare"      | IOException           | operation()            | null                                               | {
      it.exchangeDeclare("some-exchange", "invalid-type", true)
    }
    "Channel.basicConsume"  | IllegalStateException | operation()            | "Invalid configuration: 'queue' must be non-null." | {
      it.basicConsume(null, null)
    }
    "basic.get <generated>" | IOException           | operationForConsumer() | null                                               | {
      it.basicGet("amq.gen-invalid-channel", true)
    }
  }

  def "test spring rabbit"() {
    setup:
    def connectionFactory = new CachingConnectionFactory(rabbitmqAddress.hostName, rabbitmqAddress.port)
    AmqpAdmin admin = new RabbitAdmin(connectionFactory)
    def queue = new Queue("some-routing-queue", false, true, true, null)
    admin.declareQueue(queue)
    AmqpTemplate template = new RabbitTemplate(connectionFactory)
    template.convertAndSend(queue.name, "foo")
    String message = (String) template.receiveAndConvert(queue.name)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    expect:
    message == "foo"

    and:
    assertTraces(3, SORT_TRACES_BY_ID) {
      trace(1) {
        rabbitSpan(it, "queue.declare")
      }
      def publishSpan = null
      trace(1) {
        publishSpan = span(0)
        rabbitSpan(it, "basic.publish <default> -> some-routing-queue", false, null, operationForProducer())
      }
      if (hasQueueSpan()) {
        trace(2) {
          rabbitSpan(it, "basic.get ${queue.name}", false, span(1), operationForConsumer())
          rabbitQueueSpan(it, "amqp.deliver ${queue.name}", true, publishSpan)
        }
      } else {
        trace(1) {
          rabbitSpan(it, "basic.get ${queue.name}", true, publishSpan, operationForConsumer())
        }
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "exchange:", "has_routing_key:true", "type:rabbitmq")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:some-routing-queue", "type:rabbitmq")
      }
    }
  }

  def "test rabbit publish/get with given disabled exchange (producer side)"() {
    setup:
    injectSysConfig(RABBIT_PROPAGATION_DISABLED_EXCHANGES, config)

    when:
    runUnderTrace("parent") {
      channel.queueDeclare(queueName, false, true, true, null)
      channel.exchangeDeclare(exchangeName, "direct", false)
      channel.queueBind(queueName, exchangeName, routingKey)
      channel.basicPublish(exchangeName, routingKey, null, "Hello, world!".bytes)
    }
    removeSysConfig(RABBIT_PROPAGATION_DISABLED_EXCHANGES)
    String body = null
    int expectedTraces = 0
    switch (type) {
      case "get":
        body = new String(channel.basicGet(queueName, true).body)
        expectedTraces = 2
        break
      case "deliver":
        def consumer = new StringConsumer(channel)
        channel.basicConsume(queueName, consumer)
        body = consumer.body
        expectedTraces = 3
        break
      default:
        break
    }
    if (isDataStreamsEnabled() && !noParent) {
      // In the noParent case, the exchange is disabled so we don't expect any pathway injections.
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    body == "Hello, world!"

    and:
    assertTraces(expectedTraces, SORT_TRACES_BY_ID) {
      def publishSpan = null
      trace(5) {
        publishSpan = span(1)
        basicSpan(it, "parent")
        rabbitSpan(it, "basic.publish $exchangeName -> $routingKey", false, span(0), operationForProducer())
        rabbitSpan(it, "queue.bind", false, span(0))
        rabbitSpan(it, "exchange.declare", false, span(0))
        rabbitSpan(it, "queue.declare", false, span(0))
      }
      if (type == "deliver") {
        trace(1) {
          rabbitSpan(it, "basic.consume")
        }
      }
      if (hasQueueSpan() && !noParent) {
        trace(2) {
          rabbitSpan(it, "basic.$type $queueName", false, span(1), operationForConsumer())
          rabbitQueueSpan(it, "amqp.deliver $queueName", true, publishSpan)
        }
      } else {
        trace(1) {
          if (noParent) {
            rabbitSpan(it, "basic.$type $queueName", false, null, operationForConsumer())
          } else {
            rabbitSpan(it, "basic.$type $queueName", true, publishSpan, operationForConsumer())
          }
        }
      }
    }

    and:
    if (isDataStreamsEnabled() && !noParent) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "exchange:" + exchangeName, "has_routing_key:true", "type:rabbitmq")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
      }
    }

    where:
    type      | exchangeName    | routingKey         | queueName       | config          | noParent
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | "queueNameTest" | false
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | "some-exchange" | true
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | ""              | false
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | "queueNameTest" | false
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | "some-exchange" | true
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | ""              | false
  }

  def "test rabbit publish/get with given disabled queue (consumer side)"() {
    setup:
    def conditions = new PollingConditions(timeout: 5)
    removeSysConfig(RABBIT_PROPAGATION_DISABLED_QUEUES)

    when:
    runUnderTrace("parent") {
      channel.queueDeclare(queueName, false, true, true, null)
      channel.exchangeDeclare(exchangeName, "direct", false)
      channel.queueBind(queueName, exchangeName, routingKey)
      channel.basicPublish(exchangeName, routingKey, null, "Hello, world!".bytes)
    }
    injectSysConfig(RABBIT_PROPAGATION_DISABLED_QUEUES, config)
    String body = null
    int expectedTraces = 0
    switch (type) {
      case "get":
        body = new String(channel.basicGet(queueName, true).body)
        expectedTraces = 2
        break
      case "deliver":
        def consumer = new StringConsumer(channel)
        channel.basicConsume(queueName, consumer)
        body = consumer.body
        expectedTraces = 3
        break
      default:
        break
    }
    if (isDataStreamsEnabled() && !noParent) {
      // In the noParent case, the queue is disabled so we don't expect any pathway injections.
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    body == "Hello, world!"

    and:
    assertTraces(expectedTraces, SORT_TRACES_BY_ID) {
      def publishSpan = null
      trace(5) {
        publishSpan = span(1)
        basicSpan(it, "parent")
        rabbitSpan(it, "basic.publish $exchangeName -> $routingKey", false, span(0), operationForProducer())
        rabbitSpan(it, "queue.bind", false, span(0))
        rabbitSpan(it, "exchange.declare", false, span(0))
        rabbitSpan(it, "queue.declare", false, span(0))
      }
      if (type == "deliver") {
        trace(1) {
          rabbitSpan(it, "basic.consume")
        }
      }
      if (hasQueueSpan() && !noParent) {
        trace(2) {
          rabbitSpan(it, "basic.$type $queueName", false, span(1), operationForConsumer())
          rabbitQueueSpan(it, "amqp.deliver $queueName", true, publishSpan)
        }
      } else {
        trace(1) {
          if (noParent) {
            rabbitSpan(it, "basic.$type $queueName", false, null, operationForConsumer())
          } else {
            rabbitSpan(it, "basic.$type $queueName", true, publishSpan, operationForConsumer())
          }
        }
      }
    }

    and:
    if (isDataStreamsEnabled() && !noParent) {
      conditions.eventually {
        // assert with retries in case DSM data is split in more groups that take a bit longer to arrive.
        StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
        verifyAll(first) {
          tags.hasAllTags("direction:out", "exchange:" + exchangeName, "has_routing_key:true", "type:rabbitmq")
        }

        StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
        verifyAll(second) {
          tags.hasAllTags("direction:in", "topic:" + queueName, "type:rabbitmq")
        }
      }
    }

    where:
    type      | exchangeName    | routingKey         | queueName       | config          | noParent
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | "queueNameTest" | true
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | "some-exchange" | false
    "get"     | "some-exchange" | "some-routing-key" | "queueNameTest" | ""              | false
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | "queueNameTest" | true
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | "some-exchange" | false
    "deliver" | "some-exchange" | "some-routing-key" | "queueNameTest" | ""              | false
  }

  def rabbitSpan(
    TraceAssert trace,
    String resource,
    Boolean distributedRootSpan = false,
    DDSpan parentSpan = null,
    String operation = operation(),
    Throwable exception = null,
    String errorMsg = null,
    Boolean expectTimestamp = false
  ) {
    internalRabbitSpan(
      trace,
      service(),
      operation,
      excludesRoutingKeyFromResource() ? resource.replaceAll(" -> .*", "") : resource,
      distributedRootSpan,
      parentSpan,
      exception,
      errorMsg,
      expectTimestamp
      )
  }

  def rabbitQueueSpan(
    TraceAssert trace,
    String resource,
    Boolean distributedRootSpan = false,
    DDSpan parentSpan = null,
    Throwable exception = null,
    String errorMsg = null,
    Boolean expectTimestamp = false
  ) {
    internalRabbitSpan(
      trace,
      splitByDestination() ? resource.replace("amqp.deliver ", "") : serviceForTimeInQueue(),
      "amqp.deliver",
      resource,
      distributedRootSpan,
      parentSpan,
      exception,
      errorMsg,
      expectTimestamp
      )
  }

  def internalRabbitSpan(
    TraceAssert trace,
    String service,
    String operation,
    String resource,
    Boolean distributedRootSpan,
    DDSpan parentSpan,
    Throwable exception,
    String errorMsg,
    Boolean expectTimestamp
  ) {
    trace.span {
      serviceName service
      operationName operation
      resourceName resource
      switch (span.tags["amqp.command"]) {
        case "basic.publish":
          spanType DDSpanTypes.MESSAGE_PRODUCER
          break
        case "basic.get":
        case "basic.deliver":
          spanType DDSpanTypes.MESSAGE_CONSUMER
          break
        default:
          if (operation == "amqp.deliver") {
            spanType DDSpanTypes.MESSAGE_BROKER
          } else {
            spanType DDSpanTypes.MESSAGE_CLIENT
          }
      }

      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }

      errored exception != null
      measured true

      final boolean isV0 = version() == 0

      tags {
        "$Tags.COMPONENT" "rabbitmq-amqp"
        "$Tags.PEER_HOSTNAME" { it == null || it instanceof String }
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" }
        "$Tags.PEER_HOST_IPV6" { it == null || it == "0:0:0:0:0:0:0:1" }
        "$Tags.PEER_PORT" { it == null || it instanceof Integer }
        if (expectTimestamp) {
          "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it instanceof Long && it >= 0 }
        }
        // FIXME: this is broken in the instrumentation
        // `it` should never be null
        "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it == null || it >= 0 }

        switch (tag("amqp.command")) {
          case "basic.publish":
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "amqp.command" "basic.publish"
            "amqp.exchange" { it == null || it == "some-exchange" || it == "some-error-exchange" }
            "amqp.routing_key" {
              it == null || it == "some-routing-key" || it == "some-routing-queue" || it.startsWith("amq.gen-")
            }
            "amqp.delivery_mode" { it == null || it == 2 }
            "message.size" Integer
            break
          case "basic.get":
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "amqp.command" "basic.get"
            "amqp.queue" { it == "some-queue" || it == "some-routing-queue" || it.startsWith("amq.gen-") || it == "queueNameTest" }
            "message.size" { it == null || it instanceof Integer }
            break
          case "basic.deliver":
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "amqp.command" "basic.deliver"
            "amqp.exchange" { it == "some-exchange" || it == "some-error-exchange" }
            "amqp.routing_key" {
              it == null || it == "some-routing-key" || it == "some-routing-queue" || it.startsWith("amq.gen-")
            }
            "message.size" Integer
            break
          default:
            if (operation == "amqp.deliver") {
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_BROKER
              "amqp.queue" { it == "some-queue" || it == "some-routing-queue" || it.startsWith("amq.gen-") || it == "queueNameTest" }
              "message.size" Integer
            } else {
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            }
            "amqp.command" { it == null || it == resource }
        }
        if (exception) {
          errorTags(exception.class, errorMsg)
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        if (isV0) {
          // in v0 the service name is always set to DD_SERVICE while it should just be unset as v1
          // this is a buggy behaviour that could not be easily fixed.
          serviceNameSource "rabbitmq-amqp"
        }
        if ([Tags.SPAN_KIND_PRODUCER, Tags.SPAN_KIND_CLIENT].any({ it == tag(Tags.SPAN_KIND) })) {
          peerServiceFrom(Tags.PEER_HOSTNAME)
          defaultTags(distributedRootSpan)
        } else {
          defaultTagsNoPeerService(distributedRootSpan)
        }
      }
    }
  }

  static class StringConsumer extends DefaultConsumer {
    private Semaphore semaphore = new Semaphore(0)
    private String body = null

    StringConsumer(Channel channel) {
      super(channel)
    }

    @Override
    void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
      super.handleDelivery(consumerTag, envelope, properties, body)
      this.body = new String(body)
      semaphore.release()
    }

    String getBody() {
      semaphore.acquire()
      return body
    }
  }
}

abstract class RabbitMQForkedTest extends RabbitMQTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "RabbitMQTest")
    injectSysConfig("dd.rabbit.legacy.tracing.enabled", "false")
  }

  @Override
  String service() {
    return "RabbitMQTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

class RabbitMQNamingV0Test extends RabbitMQForkedTest {
}

class RabbitMQNamingV1ForkedTest extends RabbitMQForkedTest {
  @Override
  String operationForProducer() {
    "amqp.send"
  }

  @Override
  String operationForConsumer() {
    "amqp.process"
  }

  @Override
  String serviceForTimeInQueue() {
    "rabbitmq-queue"
  }

  @Override
  int version() {
    1
  }

  @Override
  boolean hasQueueSpan() {
    false
  }
}

class RabbitMQDatastreamsDisabledForkedTest extends RabbitMQTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "RabbitMQDatastreamsDisabledForkedTest")
    injectSysConfig("dd.rabbit.legacy.tracing.enabled", "false")
  }

  @Override
  String service() {
    return "RabbitMQDatastreamsDisabledForkedTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }

  @Override
  boolean isDataStreamsEnabled() {
    false
  }
}

class RabbitMQSplitByDestinationForkedTest extends RabbitMQTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "RabbitMQTest")
    injectSysConfig("dd.rabbit.legacy.tracing.enabled", "false")
    injectSysConfig("dd.message.broker.split-by-destination", "true")
  }

  @Override
  String service() {
    return "RabbitMQTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return true
  }
}

class RabbitMQLegacyTracingV0ForkedTest extends RabbitMQTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.rabbit.legacy.tracing.enabled", "true")
  }

  @Override
  String service() {
    return "rabbitmq"
  }

  @Override
  boolean hasQueueSpan() {
    return false
  }

  @Override
  boolean splitByDestination() {
    return false
  }
}

class RabbitMQLegacyTracingV1ForkedTest extends RabbitMQLegacyTracingV0ForkedTest {
  @Override
  String operationForProducer() {
    "amqp.send"
  }

  @Override
  String operationForConsumer() {
    "amqp.process"
  }

  @Override
  String service() {
    Config.get().getServiceName()
  }

  @Override
  int version() {
    1
  }
}

class RabbitMQRoutingKeyExcludedForkedTest extends RabbitMQTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.service", "RabbitMQRoutingKeyExcludedForkedTest")
    injectSysConfig("dd.rabbit.include.routingkey.in.resource", "false")
    injectSysConfig("dd.rabbit.legacy.tracing.enabled", "false")
  }

  @Override
  String service() {
    return "RabbitMQRoutingKeyExcludedForkedTest"
  }

  @Override
  boolean hasQueueSpan() {
    return true
  }

  @Override
  boolean splitByDestination() {
    return false
  }

  @Override
  boolean excludesRoutingKeyFromResource() {
    return true
  }
}

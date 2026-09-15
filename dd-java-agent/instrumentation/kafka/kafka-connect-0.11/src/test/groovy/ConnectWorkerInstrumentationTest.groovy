import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.datastreams.StatsGroup
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.DescribeClusterResult
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.utils.Time
import org.apache.kafka.connect.connector.policy.AllConnectorClientConfigOverridePolicy
import org.apache.kafka.connect.connector.policy.ConnectorClientConfigOverridePolicy
import org.apache.kafka.connect.runtime.Herder
import org.apache.kafka.connect.runtime.Worker
import org.apache.kafka.connect.runtime.WorkerConfig
import org.apache.kafka.connect.runtime.isolation.Plugins
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo
import org.apache.kafka.connect.runtime.standalone.StandaloneConfig
import org.apache.kafka.connect.runtime.standalone.StandaloneHerder
import org.apache.kafka.connect.storage.FileOffsetBackingStore
import org.apache.kafka.connect.util.Callback
import org.springframework.kafka.test.EmbeddedKafkaBroker
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectWorkerInstrumentationTest extends InstrumentationSpecification {
  private static final String SOURCE_TOPIC = 'source-test-topic'
  private static final String SINK_TOPIC = 'sink-test-topic'

  @Shared
  EmbeddedKafkaBroker embeddedKafka = new EmbeddedKafkaBroker(1, false, 1, SOURCE_TOPIC, SINK_TOPIC)

  def setupSpec() {
    embeddedKafka.afterPropertiesSet() // Initializes the broker
  }

  def cleanupSpec() {
    embeddedKafka.destroy()
  }

  def "test kafka-connect instrumentation"() {
    // Kafka bootstrap servers from the embedded broker
    String bootstrapServers = embeddedKafka.getBrokersAsString()

    // Retrieve Kafka cluster ID
    // Create an AdminClient to interact with the Kafka cluster
    Properties adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    String clusterId
    try (AdminClient adminClient = AdminClient.create(adminProps)) {
      DescribeClusterResult describeClusterResult = adminClient.describeCluster()
      clusterId = describeClusterResult.clusterId().get() // Retrieve the cluster ID
    }
    assert clusterId != null : "Cluster ID is null"

    // Create a temporary file with a test message
    File tempFile = File.createTempFile("test-message", ".txt")

    // Worker properties
    Properties workerProps = new Properties()
    workerProps.put(WorkerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    workerProps.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG, "/tmp/connect.offsets")
    workerProps.put(WorkerConfig.INTERNAL_KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter")
    workerProps.put(WorkerConfig.INTERNAL_VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter")
    workerProps.put(WorkerConfig.PLUGIN_PATH_CONFIG, "") // Required but can be empty for built-in connectors
    workerProps.put("plugin.scan.classpath", "true")

    Map<String, String> workerPropsMap = workerProps.stringPropertyNames()
    .collectEntries {
      [(it): workerProps.getProperty(it)]
    }

    // Create the Connect worker
    Time time = Time.SYSTEM
    Plugins plugins = new Plugins(workerPropsMap)
    plugins.compareAndSwapWithDelegatingLoader()
    String workerId = "worker-1"

    FileOffsetBackingStore offsetBackingStore = new FileOffsetBackingStore()
    WorkerConfig workerConfig = new StandaloneConfig(workerPropsMap)
    offsetBackingStore.configure(workerConfig)
    ConnectorClientConfigOverridePolicy connectorClientConfigOverridePolicy = new AllConnectorClientConfigOverridePolicy()
    Worker worker = new Worker(workerId, time, plugins, workerConfig, offsetBackingStore, connectorClientConfigOverridePolicy)
    Herder herder = new StandaloneHerder(worker, clusterId, connectorClientConfigOverridePolicy)

    // Start worker and herder
    worker.start()
    herder.start()

    // Connector configuration
    Map<String, String> connectorProps = [
      'name'          : 'file-source-connector',
      'connector.class': 'org.apache.kafka.connect.file.FileStreamSourceConnector',
      'tasks.max'     : '1',
      'file'          : tempFile.getAbsolutePath(),
      'topic'         : SOURCE_TOPIC
    ]

    // Latch to wait for connector addition
    CountDownLatch connectorAddedLatch = new CountDownLatch(1)
    Callback<Herder.Created<ConnectorInfo>> addConnectorCallback = new Callback<Herder.Created<ConnectorInfo>>() {
      @Override
      void onCompletion(Throwable error, Herder.Created<ConnectorInfo> result) {
        if (error != null) {
          error.printStackTrace()
        } else {
          println "Connector added successfully."
        }
        connectorAddedLatch.countDown()
      }
    }

    when:
    // Add the connector to the herder
    herder.putConnectorConfig("file-source-connector", connectorProps, false, addConnectorCallback)

    // Wait for the connector to be added
    boolean connectorAdded = connectorAddedLatch.await(10, TimeUnit.SECONDS)
    assert connectorAdded : "Connector was not added in time"

    tempFile.write("Hello Kafka\n")

    // Consume the message from Kafka
    Properties consumerProps = new Properties()
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group")
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")

    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)
    consumer.subscribe([SOURCE_TOPIC])

    String receivedMessage = null
    for (int i = 0; i < 10; i++) {
      // Try for up to 10 seconds
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1))
      if (!records.isEmpty()) {
        receivedMessage = records.iterator().next().value()
        break
      }
    }
    then:
    receivedMessage == "Hello Kafka"

    List<StatsGroup> pathway = waitForPathway(SOURCE_TOPIC, "test-consumer-group")
    StatsGroup first = pathway[0]
    verifyAll(first) {
      tags.hasAllTags(
      "direction:out",
      "topic:" + SOURCE_TOPIC,
      "type:kafka"
      )
    }

    StatsGroup second = pathway[1]
    verifyAll(second) {
      tags.hasAllTags("direction:in", "group:test-consumer-group", "topic:" + SOURCE_TOPIC, "type:kafka")
    }
    TEST_DATA_STREAMS_WRITER.getServices().contains('file-source-connector')

    cleanup:
    consumer?.close()
    herder?.stop()
    worker?.stop()
    tempFile?.delete()
  }

  def "test kafka-connect sink instrumentation"() {
    String bootstrapServers = embeddedKafka.getBrokersAsString()

    Properties adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    String clusterId
    try (AdminClient adminClient = AdminClient.create(adminProps)) {
      DescribeClusterResult describeClusterResult = adminClient.describeCluster()
      clusterId = describeClusterResult.clusterId().get()
    }
    assert clusterId != null : "Cluster ID is null"

    // Create a temporary file where the sink connector should write
    File sinkFile = File.createTempFile("sink-messages", ".txt")
    if (sinkFile.exists()) {
      sinkFile.delete()
    }
    sinkFile.deleteOnExit()

    Properties workerProps = new Properties()
    workerProps.put(WorkerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    workerProps.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG, "/tmp/connect.offsets")
    workerProps.put(WorkerConfig.INTERNAL_KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter")
    workerProps.put(WorkerConfig.INTERNAL_VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter")
    workerProps.put(WorkerConfig.PLUGIN_PATH_CONFIG, "") // Required but can be empty for built-in connectors
    workerProps.put("plugin.scan.classpath", "true")

    Map<String, String> workerPropsMap = workerProps.stringPropertyNames()
    .collectEntries {
      [(it): workerProps.getProperty(it)]
    }

    // Create the Connect worker
    Time time = Time.SYSTEM
    Plugins plugins = new Plugins(workerPropsMap)
    plugins.compareAndSwapWithDelegatingLoader()
    String workerId = "worker-1"

    FileOffsetBackingStore offsetBackingStore = new FileOffsetBackingStore()
    WorkerConfig workerConfig = new StandaloneConfig(workerPropsMap)
    offsetBackingStore.configure(workerConfig)
    ConnectorClientConfigOverridePolicy connectorClientConfigOverridePolicy = new AllConnectorClientConfigOverridePolicy()
    Worker worker = new Worker(workerId, time, plugins, workerConfig, offsetBackingStore, connectorClientConfigOverridePolicy)
    Herder herder = new StandaloneHerder(worker, clusterId, connectorClientConfigOverridePolicy)

    // Start worker and herder
    worker.start()
    herder.start()

    // Create the sink connector configuration
    Map<String, String> connectorProps = [
      'name'           : 'file-sink-connector',
      'connector.class': 'org.apache.kafka.connect.file.FileStreamSinkConnector',
      'tasks.max'      : '1',
      'file'           : sinkFile.getAbsolutePath(),
      'topics'         : SINK_TOPIC
    ]

    // Latch to wait for connector addition
    CountDownLatch connectorAddedLatch = new CountDownLatch(1)
    Callback<Herder.Created<ConnectorInfo>> addConnectorCallback = new Callback<Herder.Created<ConnectorInfo>>() {
      @Override
      void onCompletion(Throwable error, Herder.Created<ConnectorInfo> result) {
        if (error != null) {
          error.printStackTrace()
        } else {
          println "Sink connector added successfully."
        }
        connectorAddedLatch.countDown()
      }
    }

    when:
    // Add the sink connector to the herder
    herder.putConnectorConfig("file-sink-connector", connectorProps, false, addConnectorCallback)

    // Wait for the connector to be added
    boolean connectorAdded = connectorAddedLatch.await(10, TimeUnit.SECONDS)
    assert connectorAdded : "Sink connector was not added in time"

    // Produce a message to the topic that we expect to be written to the file
    Properties producerProps = new Properties()
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")

    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)
    producer.send(new ProducerRecord<>(SINK_TOPIC, "key1", "Hello Kafka Sink"))
    producer.flush()
    producer.close()

    for (int i = 0; i < 100; i++) {
      // Try for up to 10 seconds
      Thread.sleep(100)
      if (sinkFile.text.contains("Hello Kafka Sink")) {
        break
      }
    }

    String fileContents = sinkFile.text
    then:
    fileContents.contains("Hello Kafka Sink")

    List<StatsGroup> pathway = waitForPathway(SINK_TOPIC, "connect-file-sink-connector")
    StatsGroup first = pathway[0]
    verifyAll(first) {
      tags.hasAllTags("direction:out", "topic:" + SINK_TOPIC, "type:kafka")
    }

    StatsGroup second = pathway[1]
    verifyAll(second) {
      tags.hasAllTags("direction:in", "group:connect-file-sink-connector", "topic:" + SINK_TOPIC, "type:kafka")
    }
    TEST_DATA_STREAMS_WRITER.getServices().contains('file-sink-connector')

    cleanup:
    herder?.stop()
    worker?.stop()
    sinkFile?.delete()
  }

  private List<StatsGroup> waitForPathway(String topic, String group) {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
    while (System.currentTimeMillis() < deadline) {
      StatsGroup producer = TEST_DATA_STREAMS_WRITER.groups.find {
        it.parentHash == 0 &&
        it.tags.hasAllTags("direction:out", "topic:" + topic, "type:kafka")
      }
      if (producer != null) {
        StatsGroup consumer = TEST_DATA_STREAMS_WRITER.groups.find {
          it.parentHash == producer.hash &&
          it.tags.hasAllTags("direction:in", "group:" + group, "topic:" + topic, "type:kafka")
        }
        if (consumer != null) {
          return [producer, consumer]
        }
      }
      Thread.sleep(20)
    }
    throw new AssertionError("No matching data-stream pathway found for topic " + topic + " and group " + group)
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }
}

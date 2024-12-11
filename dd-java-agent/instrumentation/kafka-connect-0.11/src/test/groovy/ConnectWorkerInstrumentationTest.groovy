import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.core.datastreams.StatsGroup
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.DescribeClusterResult
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.utils.Time
import org.apache.kafka.connect.connector.policy.AllConnectorClientConfigOverridePolicy
import org.apache.kafka.connect.connector.policy.ConnectorClientConfigOverridePolicy
import org.apache.kafka.connect.runtime.Herder
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo
import org.apache.kafka.connect.runtime.standalone.StandaloneConfig
import org.apache.kafka.connect.runtime.standalone.StandaloneHerder
import org.apache.kafka.connect.runtime.Worker
import org.apache.kafka.connect.runtime.WorkerConfig
import org.apache.kafka.connect.runtime.isolation.Plugins
import org.apache.kafka.connect.storage.FileOffsetBackingStore
import org.apache.kafka.connect.util.Callback
import org.springframework.kafka.test.EmbeddedKafkaBroker
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectWorkerInstrumentationTest extends AgentTestRunner {
  @Shared
  EmbeddedKafkaBroker embeddedKafka = new EmbeddedKafkaBroker(1, false, 1, 'test-topic')

  def setupSpec() {
    embeddedKafka.afterPropertiesSet() // Initializes the broker
  }

  def cleanupSpec() {
    embeddedKafka.destroy()
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
  }

  def "test kafka-connect instrumentation"() {
    // Kafka bootstrap servers from the embedded broker
    String bootstrapServers = embeddedKafka.getBrokersAsString()

    // Retrieve Kafka cluster ID
    // Create an AdminClient to interact with the Kafka cluster
    Properties adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    String clusterId = null
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
    .collectEntries { [(it): workerProps.getProperty(it)] }

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
      'topic'         : 'test-topic'
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
    consumer.subscribe(['test-topic'])

    String receivedMessage = null
    for (int i = 0; i < 10; i++) { // Try for up to 10 seconds
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1))
      if (!records.isEmpty()) {
        receivedMessage = records.iterator().next().value()
        break
      }
    }
    TEST_DATA_STREAMS_WRITER.waitForGroups(2)

    then:
    receivedMessage == "Hello Kafka"

    StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
    verifyAll(first) {
      assert [
        "direction:out",
        "topic:test-topic",
        "type:kafka"
      ].every( tag -> edgeTags.contains(tag) )
    }

    StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
    verifyAll(second) {
      assert [
        "direction:in",
        "group:test-consumer-group",
        "topic:test-topic",
        "type:kafka"
      ].every( tag -> edgeTags.contains(tag) )
    }
    TEST_DATA_STREAMS_WRITER.getServices().contains('file-source-connector')


    cleanup:
    consumer?.close()
    herder?.stop()
    worker?.stop()
    tempFile?.delete()
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }
}

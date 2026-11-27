import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.kafka_connect.SpawningSourceConnector
import datadog.trace.instrumentation.kafka_connect.SpawningSourceTask
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
import org.apache.kafka.connect.runtime.TaskStatus
import org.apache.kafka.connect.runtime.WorkerConfig
import org.apache.kafka.connect.runtime.distributed.DistributedConfig
import org.apache.kafka.connect.runtime.isolation.Plugins
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo
import org.apache.kafka.connect.runtime.standalone.StandaloneConfig
import org.apache.kafka.connect.runtime.standalone.StandaloneHerder
import org.apache.kafka.connect.json.JsonConverter
import org.apache.kafka.connect.storage.FileOffsetBackingStore
import org.apache.kafka.connect.storage.KafkaStatusBackingStore
import org.apache.kafka.connect.util.Callback
import org.apache.kafka.connect.util.ConnectorTaskId
import org.springframework.kafka.test.EmbeddedKafkaBroker
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectWorkerInstrumentationTest extends InstrumentationSpecification {
  private static final String SOURCE_TOPIC = 'source-test-topic'
  private static final String SINK_TOPIC = 'sink-test-topic'
  private static final String SPAWNED_TOPIC = 'spawned-test-topic'
  private static final String NORMAL_TOPIC = 'normal-test-topic'
  private static final String STATUS_TOPIC = 'test-cluster-status'
  // MirrorMaker 2 and default-named Connect topics that are matched by name, not config
  private static final List<String> NAMED_INTERNAL_TOPICS = [
    'mm2-offset-syncs.source.internal',
    'source.checkpoints.internal',
    'heartbeats',
    'source.heartbeats',
    '_connect-offsets'
  ]

  @Shared
  EmbeddedKafkaBroker embeddedKafka = new EmbeddedKafkaBroker(1, false, 1, [SOURCE_TOPIC, SINK_TOPIC, SPAWNED_TOPIC, NORMAL_TOPIC] + NAMED_INTERNAL_TOPICS as String[])

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
    File offsetFile = new File(tempFile.parentFile, "connect-source-offsets-${UUID.randomUUID()}")
    offsetFile.deleteOnExit()

    // Worker properties
    Properties workerProps = new Properties()
    workerProps.put(WorkerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    workerProps.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG, offsetFile.absolutePath)
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
    offsetFile?.delete()
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
    File offsetFile = new File(sinkFile.parentFile, "connect-sink-offsets-${UUID.randomUUID()}")
    offsetFile.deleteOnExit()

    Properties workerProps = new Properties()
    workerProps.put(WorkerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    workerProps.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.storage.StringConverter")
    workerProps.put(StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG, offsetFile.absolutePath)
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
    offsetFile?.delete()
  }

  def "test thread spawned by a task reports the connector service name"() {
    String bootstrapServers = embeddedKafka.getBrokersAsString()
    String clusterId = getClusterId(bootstrapServers)
    File offsetFile = File.createTempFile("connect-spawn-offsets", ".txt")
    offsetFile.deleteOnExit()

    Map<String, String> workerPropsMap = standaloneWorkerProps(bootstrapServers, offsetFile)
    Plugins plugins = new Plugins(workerPropsMap)
    plugins.compareAndSwapWithDelegatingLoader()
    FileOffsetBackingStore offsetBackingStore = new FileOffsetBackingStore()
    WorkerConfig workerConfig = new StandaloneConfig(workerPropsMap)
    offsetBackingStore.configure(workerConfig)
    ConnectorClientConfigOverridePolicy policy = new AllConnectorClientConfigOverridePolicy()
    Worker worker = new Worker("worker-1", Time.SYSTEM, plugins, workerConfig, offsetBackingStore, policy)
    Herder herder = new StandaloneHerder(worker, clusterId, policy)
    worker.start()
    herder.start()

    Map<String, String> connectorProps = [
      'name'                                 : 'spawning-connector',
      'connector.class'                      : SpawningSourceConnector.name,
      'tasks.max'                            : '1',
      (SpawningSourceTask.BOOTSTRAP_SERVERS) : bootstrapServers,
      (SpawningSourceTask.TOPIC)             : SPAWNED_TOPIC
    ]
    CountDownLatch connectorAddedLatch = new CountDownLatch(1)
    Callback<Herder.Created<ConnectorInfo>> callback = new Callback<Herder.Created<ConnectorInfo>>() {
      @Override
      void onCompletion(Throwable error, Herder.Created<ConnectorInfo> result) {
        error?.printStackTrace()
        connectorAddedLatch.countDown()
      }
    }

    when:
    herder.putConnectorConfig("spawning-connector", connectorProps, false, callback)
    assert connectorAddedLatch.await(10, TimeUnit.SECONDS) : "Connector was not added in time"

    then:
    StatsGroup produced = waitForGroup('spawning-connector', "direction:out", "topic:" + SPAWNED_TOPIC, "type:kafka")
    produced != null
    TEST_DATA_STREAMS_WRITER.getGroupsForService(null).every {
      !it.tags.hasAllTags("direction:out", "topic:" + SPAWNED_TOPIC)
    }

    cleanup:
    herder?.stop()
    worker?.stop()
    offsetFile?.delete()
  }

  def "test kafka-connect internal topics get no checkpoints"() {
    String bootstrapServers = embeddedKafka.getBrokersAsString()
    Map<String, String> distributedProps = [
      (WorkerConfig.BOOTSTRAP_SERVERS_CONFIG)                    : bootstrapServers,
      (WorkerConfig.KEY_CONVERTER_CLASS_CONFIG)                  : 'org.apache.kafka.connect.storage.StringConverter',
      (WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG)                : 'org.apache.kafka.connect.storage.StringConverter',
      (DistributedConfig.GROUP_ID_CONFIG)                        : 'internal-topics-test',
      (DistributedConfig.OFFSET_STORAGE_TOPIC_CONFIG)            : 'test-cluster-offsets',
      (DistributedConfig.CONFIG_TOPIC_CONFIG)                    : 'test-cluster-configs',
      (DistributedConfig.STATUS_STORAGE_TOPIC_CONFIG)            : STATUS_TOPIC,
      (DistributedConfig.STATUS_STORAGE_REPLICATION_FACTOR_CONFIG): '1',
      (DistributedConfig.STATUS_STORAGE_PARTITIONS_CONFIG)       : '1'
    ]
    // Reading the worker config registers the configured internal topic names
    DistributedConfig distributedConfig = new DistributedConfig(distributedProps)
    JsonConverter internalConverter = new JsonConverter()
    internalConverter.configure(['schemas.enable': 'false'], false)
    KafkaStatusBackingStore statusStore = new KafkaStatusBackingStore(Time.SYSTEM, internalConverter)
    statusStore.configure(distributedConfig)
    statusStore.start()
    ConnectorTaskId taskId = new ConnectorTaskId('status-connector', 0)

    Properties producerProps = new Properties()
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    Properties consumerProps = new Properties()
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "internal-topics-consumer")
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)

    when:
    // Connect writes the status topic and reads it back with its own consumer
    statusStore.put(new TaskStatus(taskId, TaskStatus.State.RUNNING, 'worker-1', 0))
    for (int i = 0; i < 100 && statusStore.get(taskId) == null; i++) {
      Thread.sleep(100)
    }
    for (String topic : NAMED_INTERNAL_TOPICS) {
      producer.send(new ProducerRecord<>(topic, "key", "internal"))
    }
    producer.send(new ProducerRecord<>(NORMAL_TOPIC, "key", "normal"))
    producer.flush()

    consumer.subscribe(NAMED_INTERNAL_TOPICS + [NORMAL_TOPIC])
    Set<String> consumedTopics = []
    for (int i = 0; i < 20 && consumedTopics.size() < NAMED_INTERNAL_TOPICS.size() + 1; i++) {
      consumer.poll(Duration.ofSeconds(1)).each { consumedTopics.add(it.topic()) }
    }
    consumer.commitSync()

    then:
    statusStore.get(taskId) != null
    consumedTopics == (NAMED_INTERNAL_TOPICS + [NORMAL_TOPIC]) as Set
    // The normal topic still gets a full pathway, which also means the earlier points were flushed
    waitForPathway(NORMAL_TOPIC, "internal-topics-consumer").size() == 2
    List<String> internalTopics = NAMED_INTERNAL_TOPICS + [STATUS_TOPIC]
    TEST_DATA_STREAMS_WRITER.groups.findAll { group ->
      internalTopics.any { group.tags.hasAllTags("topic:" + it) }
    }.isEmpty()
    TEST_DATA_STREAMS_WRITER.backlogs.findAll { tags ->
      internalTopics.any { tags.hasAllTags("topic:" + it) }
    }.isEmpty()

    cleanup:
    consumer?.close()
    producer?.close()
    statusStore?.stop()
  }

  private static String getClusterId(String bootstrapServers) {
    Properties adminProps = new Properties()
    adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    try (AdminClient adminClient = AdminClient.create(adminProps)) {
      return adminClient.describeCluster().clusterId().get()
    }
  }

  private static Map<String, String> standaloneWorkerProps(String bootstrapServers, File offsetFile) {
    return [
      (WorkerConfig.BOOTSTRAP_SERVERS_CONFIG)                : bootstrapServers,
      (WorkerConfig.KEY_CONVERTER_CLASS_CONFIG)              : "org.apache.kafka.connect.storage.StringConverter",
      (WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG)            : "org.apache.kafka.connect.storage.StringConverter",
      (StandaloneConfig.OFFSET_STORAGE_FILE_FILENAME_CONFIG) : offsetFile.absolutePath,
      (WorkerConfig.INTERNAL_KEY_CONVERTER_CLASS_CONFIG)     : "org.apache.kafka.connect.json.JsonConverter",
      (WorkerConfig.INTERNAL_VALUE_CONVERTER_CLASS_CONFIG)   : "org.apache.kafka.connect.json.JsonConverter",
      (WorkerConfig.PLUGIN_PATH_CONFIG)                      : "",
      "plugin.scan.classpath"                                : "true"
    ]
  }

  private StatsGroup waitForGroup(String service, String... tags) {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
    while (System.currentTimeMillis() < deadline) {
      StatsGroup group = TEST_DATA_STREAMS_WRITER.getGroupsForService(service).find { it.tags.hasAllTags(tags) }
      if (group != null) {
        return group
      }
      Thread.sleep(20)
    }
    throw new AssertionError("No data-stream group with tags " + tags + " for service " + service)
  }

  private List<StatsGroup> waitForPathway(String topic, String group) {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
    while (System.currentTimeMillis() < deadline) {
      List<StatsGroup> groups = TEST_DATA_STREAMS_WRITER.groups
      StatsGroup producer = groups.find {
        it.parentHash == 0 &&
        it.tags.hasAllTags("direction:out", "topic:" + topic, "type:kafka")
      }
      if (producer != null) {
        StatsGroup consumer = groups.find {
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

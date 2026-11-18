import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.test.KafkaClusterTestKit;
import org.apache.kafka.common.test.TestKitNodes;

/** Uses Kafka's matching testkit without depending on Spring's embedded broker wrapper. */
public class KafkaTestBroker {
  private final int brokerCount;
  private final int partitionsPerTopic;
  private final String[] topics;
  private final Map<String, Object> brokerProperties = new HashMap<>();
  private KafkaClusterTestKit cluster;

  public KafkaTestBroker(int brokerCount, int partitionsPerTopic, String... topics) {
    this.brokerCount = brokerCount;
    this.partitionsPerTopic = partitionsPerTopic;
    this.topics = topics.clone();
    brokerProperties.put("delete.topic.enable", "true");
    brokerProperties.put("group.initial.rebalance.delay.ms", "0");
    brokerProperties.put("offsets.topic.replication.factor", Integer.toString(brokerCount));
    brokerProperties.put("transaction.state.log.replication.factor", Integer.toString(brokerCount));
    brokerProperties.put("transaction.state.log.min.isr", "1");
    brokerProperties.put("num.partitions", Integer.toString(partitionsPerTopic));
  }

  public KafkaTestBroker brokerProperty(String name, Object value) {
    brokerProperties.put(name, value);
    return this;
  }

  public void afterPropertiesSet() {
    try {
      TestKitNodes nodes =
          new TestKitNodes.Builder()
              .setCombined(true)
              .setNumBrokerNodes(brokerCount)
              .setNumControllerNodes(brokerCount)
              .build();
      KafkaClusterTestKit.Builder builder = new KafkaClusterTestKit.Builder(nodes);
      brokerProperties.forEach(builder::setConfigProp);
      cluster = builder.build();
      cluster.format();
      cluster.startup();
      cluster.waitForReadyBrokers();
      addTopics(
          Arrays.stream(topics)
              .map(topic -> new NewTopic(topic, partitionsPerTopic, (short) brokerCount))
              .toArray(NewTopic[]::new));
    } catch (Exception failure) {
      try {
        destroy();
      } catch (Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure("Could not start Kafka test broker", failure);
    }
  }

  public void addTopics(NewTopic... newTopics) {
    try (Admin admin =
        Admin.create(
            Collections.singletonMap(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBrokersAsString()))) {
      admin.createTopics(Arrays.asList(newTopics)).all().get(60, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw failure("Could not create Kafka test topics", failure);
    }
  }

  public String getBrokersAsString() {
    return cluster.bootstrapServers();
  }

  public int getPartitionsPerTopic() {
    return partitionsPerTopic;
  }

  public void destroy() {
    if (cluster != null) {
      try {
        cluster.close();
      } catch (Exception failure) {
        throw failure("Could not stop Kafka test broker", failure);
      } finally {
        cluster = null;
      }
    }
  }

  private static IllegalStateException failure(String message, Exception cause) {
    if (cause instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return new IllegalStateException(message, cause);
  }
}

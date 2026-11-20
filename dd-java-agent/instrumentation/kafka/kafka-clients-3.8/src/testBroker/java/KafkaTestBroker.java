import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

public class KafkaTestBroker extends EmbeddedKafkaKraftBroker {
  public KafkaTestBroker(int brokerCount, int partitionsPerTopic, String... topics) {
    super(brokerCount, partitionsPerTopic, topics);
  }
}

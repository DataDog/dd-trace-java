package datadog.trace.instrumentation.kafka_connect;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

/** Starts a thread in {@link #start} that sends one record with its own producer. */
public class SpawningSourceTask extends SourceTask {
  public static final String BOOTSTRAP_SERVERS = "spawn.bootstrap.servers";
  public static final String TOPIC = "spawn.topic";

  private Thread spawned;

  @Override
  public String version() {
    return "test";
  }

  @Override
  public void start(Map<String, String> props) {
    String bootstrapServers = props.get(BOOTSTRAP_SERVERS);
    String topic = props.get(TOPIC);
    spawned =
        new Thread(
            () -> {
              Properties producerProps = new Properties();
              producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
              producerProps.put(
                  ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
              producerProps.put(
                  ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
              try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
                producer.send(new ProducerRecord<>(topic, "key", "from spawned thread"));
                producer.flush();
              }
            },
            "spawned-coordinator");
    spawned.start();
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    Thread.sleep(100);
    return null;
  }

  @Override
  public void stop() {}
}

package datadog.smoketest.kafka.iast;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class IastConfiguration {

  public static final String GROUP_ID = "iast";

  public static final String STRING_TOPIC = "iast_string";

  public static final String JSON_TOPIC = "iast_json";

  @Value("${spring.kafka.bootstrap-servers}")
  private String boostrapServers;

  @Bean
  public KafkaAdmin.NewTopics iastTopics() {
    return new KafkaAdmin.NewTopics(
        TopicBuilder.name(STRING_TOPIC).partitions(1).replicas(1).compact().build(),
        TopicBuilder.name(JSON_TOPIC).partitions(1).replicas(1).compact().build());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, IastMessage> iastStringListenerFactory() {
    final Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    final DefaultKafkaConsumerFactory<String, IastMessage> consumerFactory =
        new DefaultKafkaConsumerFactory<>(config);
    ConcurrentKafkaListenerContainerFactory<String, IastMessage> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, IastMessage> iastJsonListenerFactory() {
    final Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
    config.put(JsonDeserializer.TRUSTED_PACKAGES, "datadog.*");
    final DefaultKafkaConsumerFactory<String, IastMessage> consumerFactory =
        new DefaultKafkaConsumerFactory<>(config);
    ConcurrentKafkaListenerContainerFactory<String, IastMessage> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }

  @Bean
  public KafkaTemplate<String, String> iastStringKafkaTemplate() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    final DefaultKafkaProducerFactory<String, String> factory =
        new DefaultKafkaProducerFactory<>(configProps);
    return new KafkaTemplate<>(factory);
  }

  @Bean
  public KafkaTemplate<String, IastMessage> iastJsonKafkaTemplate() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    final DefaultKafkaProducerFactory<String, IastMessage> factory =
        new DefaultKafkaProducerFactory<>(configProps);
    return new KafkaTemplate<>(factory);
  }
}

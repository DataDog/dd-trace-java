package datadog.smoketest.kafka.iast;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.ByteBufferDeserializer;
import org.apache.kafka.common.serialization.ByteBufferSerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
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

  public static final String BYTE_ARRAY_TOPIC = "iast_byteArray";

  public static final String BYTE_BUFFER_TOPIC = "iast_byteBuffer";

  public static final String JSON_TOPIC = "iast_json";

  @Value("${spring.kafka.bootstrap-servers}")
  private String boostrapServers;

  @Bean
  public KafkaAdmin.NewTopics iastTopics() {
    return new KafkaAdmin.NewTopics(
        newTopic(STRING_TOPIC),
        newTopic(BYTE_ARRAY_TOPIC),
        newTopic(BYTE_BUFFER_TOPIC),
        newTopic(JSON_TOPIC));
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> iastStringListenerFactory() {
    return listenerFor(StringDeserializer.class, config -> {});
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> iastByteArrayListenerFactory() {
    return listenerFor(ByteArrayDeserializer.class, config -> {});
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<ByteBuffer, ByteBuffer>
      iastByteBufferListenerFactory() {
    return listenerFor(ByteBufferDeserializer.class, config -> {});
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<IastMessage, IastMessage> iastJsonListenerFactory() {
    return listenerFor(
        JsonDeserializer.class,
        config -> config.put(JsonDeserializer.TRUSTED_PACKAGES, "datadog.*"));
  }

  @Bean
  public KafkaTemplate<String, String> iastStringKafkaTemplate() {
    return templateFor(StringSerializer.class);
  }

  @Bean
  public KafkaTemplate<byte[], byte[]> iastByteArrayKafkaTemplate() {
    return templateFor(ByteArraySerializer.class);
  }

  @Bean
  public KafkaTemplate<ByteBuffer, ByteBuffer> iastByteBufferKafkaTemplate() {
    return templateFor(ByteBufferSerializer.class);
  }

  @Bean
  public KafkaTemplate<IastMessage, IastMessage> iastJsonKafkaTemplate() {
    return templateFor(JsonSerializer.class);
  }

  @SuppressWarnings("rawtypes")
  private <E> ConcurrentKafkaListenerContainerFactory<E, E> listenerFor(
      final Class<? extends Deserializer> deserializer,
      final Consumer<Map<String, Object>> consumer) {
    final Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, deserializer);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer);
    consumer.accept(config);
    final DefaultKafkaConsumerFactory<E, E> consumerFactory =
        new DefaultKafkaConsumerFactory<>(config);
    ConcurrentKafkaListenerContainerFactory<E, E> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    return factory;
  }

  @SuppressWarnings("rawtypes")
  private <E> KafkaTemplate<E, E> templateFor(final Class<? extends Serializer> serializer) {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, serializer);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializer);
    final DefaultKafkaProducerFactory<E, E> factory =
        new DefaultKafkaProducerFactory<>(configProps);
    return new KafkaTemplate<>(factory);
  }

  private NewTopic newTopic(final String name) {
    return TopicBuilder.name(name).partitions(1).replicas(1).compact().build();
  }
}

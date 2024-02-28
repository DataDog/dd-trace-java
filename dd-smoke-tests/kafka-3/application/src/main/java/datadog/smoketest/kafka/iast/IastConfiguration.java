package datadog.smoketest.kafka.iast;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class IastConfiguration {

  public static final String STRING_TOPIC = "iast_string";

  public static final String BYTE_ARRAY_TOPIC = "iast_byteArray";

  public static final String BYTE_BUFFER_TOPIC = "iast_byteBuffer";

  public static final String JSON_TOPIC = "iast_json";

  public static final String REPLY_STRING_TOPIC = "iast_string_reply";

  public static final String REPLY_BYTE_ARRAY_TOPIC = "iast_byteArray_reply";

  public static final String REPLY_BYTE_BUFFER_TOPIC = "iast_byteBuffer_reply";

  public static final String REPLY_JSON_TOPIC = "iast_json_reply";

  @Value("${spring.kafka.bootstrap-servers}")
  private String boostrapServers;

  @Bean
  public KafkaAdmin.NewTopics iastTopics() {
    return new KafkaAdmin.NewTopics(
        newTopic(STRING_TOPIC),
        newTopic(BYTE_ARRAY_TOPIC),
        newTopic(BYTE_BUFFER_TOPIC),
        newTopic(JSON_TOPIC),
        newTopic(REPLY_STRING_TOPIC),
        newTopic(REPLY_BYTE_ARRAY_TOPIC),
        newTopic(REPLY_BYTE_BUFFER_TOPIC),
        newTopic(REPLY_JSON_TOPIC));
  }

  @Bean
  public DefaultKafkaConsumerFactory<String, String> iastStringConsumer() {
    return consumerFor(STRING_TOPIC, StringDeserializer.class, StringDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<byte[], byte[]> iastByteArrayConsumer() {
    return consumerFor(BYTE_ARRAY_TOPIC, ByteArrayDeserializer.class, ByteArrayDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<ByteBuffer, ByteBuffer> iastByteBufferConsumer() {
    return consumerFor(
        BYTE_BUFFER_TOPIC, ByteBufferDeserializer.class, ByteBufferDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<IastMessage, IastMessage> iastJsonConsumer() {
    final Class<? extends Deserializer<IastMessage>> deserializer = jsonDeserializer();
    return consumerFor(JSON_TOPIC, deserializer, deserializer);
  }

  @Bean
  public DefaultKafkaConsumerFactory<String, String> iastReplyStringConsumer() {
    return consumerFor(REPLY_STRING_TOPIC, StringDeserializer.class, StringDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<byte[], String> iastReplyByteArrayConsumer() {
    return consumerFor(
        REPLY_BYTE_ARRAY_TOPIC, ByteArrayDeserializer.class, StringDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<ByteBuffer, String> iastReplyByteBufferConsumer() {
    return consumerFor(
        REPLY_BYTE_BUFFER_TOPIC, ByteBufferDeserializer.class, StringDeserializer.class);
  }

  @Bean
  public DefaultKafkaConsumerFactory<IastMessage, String> iastReplyJsonConsumer() {
    return consumerFor(REPLY_JSON_TOPIC, jsonDeserializer(), StringDeserializer.class);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> iastStringListener() {
    final ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastStringConsumer());
    factory.setReplyTemplate(iastReplyStringTemplate());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<byte[], byte[]> iastByteArrayListener() {
    final ConcurrentKafkaListenerContainerFactory<byte[], byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastByteArrayConsumer());
    factory.setReplyTemplate(iastReplyByteArrayTemplate());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<ByteBuffer, ByteBuffer> iastByteBufferListener() {
    final ConcurrentKafkaListenerContainerFactory<ByteBuffer, ByteBuffer> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastByteBufferConsumer());
    factory.setReplyTemplate(iastReplyByteBufferTemplate());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<IastMessage, IastMessage> iastJsonListener() {
    final ConcurrentKafkaListenerContainerFactory<IastMessage, IastMessage> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastJsonConsumer());
    factory.setReplyTemplate(iastReplyJsonTemplate());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> iastReplyStringListener() {
    final ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastReplyStringConsumer());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<byte[], String> iastReplyByteArrayListener() {
    final ConcurrentKafkaListenerContainerFactory<byte[], String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastReplyByteArrayConsumer());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<ByteBuffer, String> iastReplyByteBufferListener() {
    final ConcurrentKafkaListenerContainerFactory<ByteBuffer, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastReplyByteBufferConsumer());
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<IastMessage, String> iastReplyJsonListener() {
    final ConcurrentKafkaListenerContainerFactory<IastMessage, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(iastReplyJsonConsumer());
    return factory;
  }

  @Bean
  public ConcurrentMessageListenerContainer<String, String> iastReplyStringContainer() {
    ConcurrentMessageListenerContainer<String, String> repliesContainer =
        iastReplyStringListener().createContainer(REPLY_STRING_TOPIC);
    repliesContainer.setAutoStartup(false);
    return repliesContainer;
  }

  @Bean
  public ConcurrentMessageListenerContainer<byte[], String> iastReplyByteArrayContainer() {
    ConcurrentMessageListenerContainer<byte[], String> repliesContainer =
        iastReplyByteArrayListener().createContainer(REPLY_BYTE_ARRAY_TOPIC);
    repliesContainer.setAutoStartup(false);
    return repliesContainer;
  }

  @Bean
  public ConcurrentMessageListenerContainer<ByteBuffer, String> iastReplyByteBufferContainer() {
    ConcurrentMessageListenerContainer<ByteBuffer, String> repliesContainer =
        iastReplyByteBufferListener().createContainer(REPLY_BYTE_BUFFER_TOPIC);
    repliesContainer.setAutoStartup(false);
    return repliesContainer;
  }

  @Bean
  public ConcurrentMessageListenerContainer<IastMessage, String> iastReplyJsonContainer() {
    ConcurrentMessageListenerContainer<IastMessage, String> repliesContainer =
        iastReplyJsonListener().createContainer(REPLY_JSON_TOPIC);
    repliesContainer.setAutoStartup(false);
    return repliesContainer;
  }

  @Bean
  public DefaultKafkaProducerFactory<String, String> iastStringProducer() {
    return producerFor(StringSerializer.class, StringSerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<byte[], byte[]> iastByteArrayProducer() {
    return producerFor(ByteArraySerializer.class, ByteArraySerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<ByteBuffer, ByteBuffer> iastByteBufferProducer() {
    return producerFor(ByteBufferSerializer.class, ByteBufferSerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<IastMessage, IastMessage> iastJsonProducer() {
    final Class<? extends Serializer<IastMessage>> serializer = jsonSerializer();
    return producerFor(serializer, serializer);
  }

  @Bean
  public DefaultKafkaProducerFactory<String, String> iastReplyStringProducer() {
    return producerFor(StringSerializer.class, StringSerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<byte[], String> iastReplyByteArrayProducer() {
    return producerFor(ByteArraySerializer.class, StringSerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<ByteBuffer, String> iastReplyByteBufferProducer() {
    return producerFor(ByteBufferSerializer.class, StringSerializer.class);
  }

  @Bean
  public DefaultKafkaProducerFactory<IastMessage, String> iastReplyJsonProducer() {
    return producerFor(jsonSerializer(), StringSerializer.class);
  }

  @Bean
  public ReplyingKafkaTemplate<String, String, String> iastStringTemplate() {
    return new ReplyingKafkaTemplate<>(iastStringProducer(), iastReplyStringContainer());
  }

  @Bean
  public ReplyingKafkaTemplate<byte[], byte[], String> iastByteArrayTemplate() {
    return new ReplyingKafkaTemplate<>(iastByteArrayProducer(), iastReplyByteArrayContainer());
  }

  @Bean
  public ReplyingKafkaTemplate<ByteBuffer, ByteBuffer, String> iastByteBufferTemplate() {
    return new ReplyingKafkaTemplate<>(iastByteBufferProducer(), iastReplyByteBufferContainer());
  }

  @Bean
  public ReplyingKafkaTemplate<IastMessage, IastMessage, String> iastJsonTemplate() {
    return new ReplyingKafkaTemplate<>(iastJsonProducer(), iastReplyJsonContainer());
  }

  @Bean
  public KafkaTemplate<String, String> iastReplyStringTemplate() {
    return new KafkaTemplate<>(iastReplyStringProducer());
  }

  @Bean
  public KafkaTemplate<byte[], String> iastReplyByteArrayTemplate() {
    return new KafkaTemplate<>(iastReplyByteArrayProducer());
  }

  @Bean
  public KafkaTemplate<ByteBuffer, String> iastReplyByteBufferTemplate() {
    return new KafkaTemplate<>(iastReplyByteBufferProducer());
  }

  @Bean
  public KafkaTemplate<IastMessage, String> iastReplyJsonTemplate() {
    return new KafkaTemplate<>(iastReplyJsonProducer());
  }

  private <K, V> DefaultKafkaProducerFactory<K, V> producerFor(
      final Class<? extends Serializer<K>> keySerializer,
      final Class<? extends Serializer<V>> valueSerializer) {
    final Map<String, Object> configProps = new HashMap<>();
    configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer);
    configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer);
    return new DefaultKafkaProducerFactory<>(configProps);
  }

  private <K, V> DefaultKafkaConsumerFactory<K, V> consumerFor(
      final String topic,
      final Class<? extends Deserializer<K>> keyDeserializer,
      final Class<? extends Deserializer<V>> valueDeserializer) {
    final Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, topic); // one group per topic
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer);
    if (JsonDeserializer.class.isAssignableFrom(keyDeserializer)
        || JsonDeserializer.class.isAssignableFrom(valueDeserializer)) {
      config.put(JsonDeserializer.TRUSTED_PACKAGES, "datadog.*");
    }
    return new DefaultKafkaConsumerFactory<>(config);
  }

  private NewTopic newTopic(final String name) {
    return TopicBuilder.name(name).partitions(1).replicas(1).build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Class<? extends Deserializer<IastMessage>> jsonDeserializer() {
    final Class<? extends Deserializer> type = JsonDeserializer.class;
    return (Class<? extends Deserializer<IastMessage>>) type;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Class<? extends Serializer<IastMessage>> jsonSerializer() {
    final Class<? extends Serializer> type = JsonSerializer.class;
    return (Class<? extends Serializer<IastMessage>>) type;
  }
}

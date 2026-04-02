package listener

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch

const val BATCH_COROUTINE_TOPIC = "batch-coroutine-topic"

@Configuration(proxyBeanMethods = false)
@EnableKafka
class KafkaBatchCoroutineConfig {

  @Bean(destroyMethod = "destroy")
  fun embeddedKafkaBroker(): EmbeddedKafkaBroker {
    val broker = EmbeddedKafkaKraftBroker(1, 2, BATCH_COROUTINE_TOPIC)
    broker.afterPropertiesSet()
    return broker
  }

  @Bean
  fun producerFactory(broker: EmbeddedKafkaBroker): DefaultKafkaProducerFactory<String, String> {
    val props = HashMap<String, Any>(KafkaTestUtils.producerProps(broker.brokersAsString))
    props["key.serializer"] = StringSerializer::class.java.name
    props["value.serializer"] = StringSerializer::class.java.name
    return DefaultKafkaProducerFactory(props)
  }

  @Bean
  fun kafkaTemplate(pf: DefaultKafkaProducerFactory<String, String>) = KafkaTemplate(pf)

  @Bean
  fun consumerFactory(broker: EmbeddedKafkaBroker): DefaultKafkaConsumerFactory<String, String> {
    val props = HashMap<String, Any>(KafkaTestUtils.consumerProps("batch-coroutine-group", "false", broker))
    props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
    props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    return DefaultKafkaConsumerFactory(props)
  }

  @Bean
  fun batchListenerContainerFactory(
    cf: DefaultKafkaConsumerFactory<String, String>
  ): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.consumerFactory = cf
    factory.isBatchListener = true
    return factory
  }

  @Bean
  fun kafkaBatchCoroutineListener() = KafkaBatchCoroutineListener()
}

@Component
class KafkaBatchCoroutineListener {

  val latch = CountDownLatch(1)
  val receivedValues = mutableListOf<String>()

  @KafkaListener(
    topics = [BATCH_COROUTINE_TOPIC],
    containerFactory = "batchListenerContainerFactory"
  )
  suspend fun consume(records: List<ConsumerRecord<String, String>>) {
    // Create a child span inside the coroutine body.
    // It should be linked to spring.consume, which should be linked to kafka.consume.
    val childSpan = startSpan("child.work")
    records.forEach { receivedValues.add(it.value()) }
    childSpan.finish()
    latch.countDown()
  }
}

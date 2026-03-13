import datadog.trace.instrumentation.kafka_common.KafkaConfigHelper
import spock.lang.Specification

class KafkaConfigHelperTest extends Specification {

  def "extractConsumerConfigFromMap includes allowed keys with values and masks others"() {
    given:
    def rawConfig = [
      "bootstrap.servers"    : "localhost:9092",
      "group.id"             : "my-group",
      "ssl.keystore.password": "secret123",
      "ssl.key.password"     : "secret456",
      "ssl.truststore.password": "secret789",
      "sasl.jaas.config"     : "org.apache.kafka.common.security.plain.PlainLoginModule required;",
      "auto.offset.reset"    : "earliest",
      "some.unknown.key"     : "unknown-value"
    ] as Map<String, Object>

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result["bootstrap.servers"] == "localhost:9092"
    result["group.id"] == "my-group"
    result["auto.offset.reset"] == "earliest"
    result["ssl.keystore.password"] == KafkaConfigHelper.MASKED_VALUE
    result["ssl.key.password"] == KafkaConfigHelper.MASKED_VALUE
    result["ssl.truststore.password"] == KafkaConfigHelper.MASKED_VALUE
    result["sasl.jaas.config"] == KafkaConfigHelper.MASKED_VALUE
    result["some.unknown.key"] == KafkaConfigHelper.MASKED_VALUE
  }

  def "non-allowed keys are masked, not dropped"() {
    given:
    def rawConfig = [:] as Map<String, Object>
    ["ssl.keystore.password", "ssl.key.password", "sasl.jaas.config", "custom.key"].each { key ->
      rawConfig[key] = "secret-value"
    }
    rawConfig["bootstrap.servers"] = "localhost:9092"

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result.size() == 5
    result["bootstrap.servers"] == "localhost:9092"
    result["ssl.keystore.password"] == KafkaConfigHelper.MASKED_VALUE
    result["ssl.key.password"] == KafkaConfigHelper.MASKED_VALUE
    result["sasl.jaas.config"] == KafkaConfigHelper.MASKED_VALUE
    result["custom.key"] == KafkaConfigHelper.MASKED_VALUE
  }

  def "all allowed keys are captured with their actual values"() {
    given:
    def rawConfig = [:] as Map<String, Object>
    KafkaConfigHelper.ALLOWED_KEYS.each { key ->
      rawConfig[key] = "test-value"
    }

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result.size() == KafkaConfigHelper.ALLOWED_KEYS.size()
    KafkaConfigHelper.ALLOWED_KEYS.each { key ->
      assert result[key] == "test-value"
    }
  }

  def "null values are converted to empty string for allowed keys"() {
    given:
    def rawConfig = [
      "bootstrap.servers": "localhost:9092",
      "client.id"        : null
    ] as Map<String, Object>

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result["bootstrap.servers"] == "localhost:9092"
    result["client.id"] == ""
  }
}

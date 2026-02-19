package datadog.trace.instrumentation.kafka_common

import spock.lang.Specification

class KafkaConfigHelperTest extends Specification {

  def "extractConsumerConfigFromMap filters sensitive keys"() {
    given:
    def rawConfig = [
      "bootstrap.servers"    : "localhost:9092",
      "group.id"             : "my-group",
      "ssl.keystore.password": "secret123",
      "ssl.key.password"     : "secret456",
      "ssl.truststore.password": "secret789",
      "sasl.jaas.config"     : "org.apache.kafka.common.security.plain.PlainLoginModule required;",
      "auto.offset.reset"    : "earliest"
    ] as Map<String, Object>

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result["bootstrap.servers"] == "localhost:9092"
    result["group.id"] == "my-group"
    result["auto.offset.reset"] == "earliest"
    !result.containsKey("ssl.keystore.password")
    !result.containsKey("ssl.key.password")
    !result.containsKey("ssl.truststore.password")
    !result.containsKey("sasl.jaas.config")
  }

  def "all sensitive keys are filtered"() {
    given:
    def rawConfig = [:] as Map<String, Object>
    KafkaConfigHelper.SENSITIVE_KEYS.each { key ->
      rawConfig[key] = "secret-value"
    }
    rawConfig["bootstrap.servers"] = "localhost:9092"

    when:
    def result = KafkaConfigHelper.extractConsumerConfigFromMap(rawConfig)

    then:
    result.size() == 1
    result["bootstrap.servers"] == "localhost:9092"
  }

  def "null values are converted to empty string"() {
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

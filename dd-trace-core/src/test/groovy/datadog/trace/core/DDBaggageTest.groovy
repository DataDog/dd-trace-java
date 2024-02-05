package datadog.trace.core

import datadog.trace.test.util.DDSpecification

class DDBaggageTest extends DDSpecification {

  def "test api"() {
    when:
    def baggage0 = DDBaggage.empty()

    then:
    baggage0.size() == 0
    baggage0.isEmpty()

    when:
    def builder = DDBaggage.builder()
      .put("key1", "value1")
      .put("key2", "value2")
    def baggage1 = builder.build()
    builder.put("key3", "value3")
    def baggage2 = builder.build()

    then:
    baggage1.size() == 2
    baggage1.get("key1") == "value1"
    baggage1.get("key2") == "value2"
    baggage1.get("key3") == null
    baggage1.asMap() == [key1: "value1", key2: "value2"]

    baggage2.size() == 3
    baggage2.get("key3") == "value3"

    when:
    def baggage3 = baggage2.toBuilder()
      .put("key1", "new-value1")
      .remove("key3")
      .put("key4", "value4")
      .build()

    then:
    baggage3.size() == 3
    baggage3.get("key1") == "new-value1"
    baggage3.get("key2") == "value2"
    baggage3.get("key3") == null
    baggage3.get("key4") == "value4"
    baggage3.asMap() == [key1: "new-value1", key2: "value2", key4: "value4"]
  }

  def "check equals/hashCode"() {
    when:
    def baggage0 = DDBaggage.empty()
    def baggage1 = baggage0.toBuilder().put("key", "value").build()
    def baggage2 = baggage1.toBuilder().put("key", "new-value").build()
    def baggage3 = baggage2.toBuilder().put("key", "value").build()

    then:
    baggage0 != baggage1
    baggage1 != baggage2
    baggage2 != baggage3
    baggage3 == baggage1

    and:
    baggage0.hashCode() != baggage1.hashCode()
    baggage1.hashCode() != baggage2.hashCode()
    baggage2.hashCode() != baggage3.hashCode()
    baggage3.hashCode() == baggage1.hashCode()
  }
}

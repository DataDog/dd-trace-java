package datadog.trace.core

import datadog.trace.test.util.DDSpecification

class BaggageTest extends DDSpecification {

  def "test api"() {
    when:
    def empty = DDBaggage.empty()

    then:
    empty.size() == 0
    empty.isEmpty()

    when:
    def builder = DDBaggage.builder()
      .put("key1", "value1")
      .put("key2", "value2")
    def baggage = builder.build()
    builder.put("key3", "value3")
    def baggage2 = builder.build()

    then:
    baggage.size() == 2
    baggage.getItemValue("key1") == "value1"
    baggage.getItemValue("key2") == "value2"
    baggage.getItemValue("key3") == null
    baggage.asMap() == [key1: "value1", key2: "value2"]

    baggage2.size() == 3
    baggage2.getItemValue("key3") == "value3"

    when:
    def baggage3 = baggage2.toBuilder()
      .put("key1", "new-value1")
      .remove("key3")
      .put("key4", "value4")
      .build()

    then:
    baggage3.size() == 3
    baggage3.getItemValue("key1") == "new-value1"
    baggage3.getItemValue("key2") == "value2"
    baggage3.getItemValue("key3") == null
    baggage3.getItemValue("key4") == "value4"
    baggage3.asMap() == [key1: "new-value1", key2: "value2", key4: "value4"]
  }
}

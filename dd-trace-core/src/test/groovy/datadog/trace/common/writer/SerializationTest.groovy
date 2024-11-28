package datadog.trace.common.writer

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.test.util.DDSpecification
import org.msgpack.core.MessagePack
import org.msgpack.jackson.dataformat.MessagePackFactory

import static java.util.Collections.singletonMap

class SerializationTest extends DDSpecification {
  def "test json mapper serialization"() {
    setup:
    def mapper = new ObjectMapper()
    def map = ["key1": "val1"]
    def serializedMap = mapper.writeValueAsBytes(map)
    def serializedList = "[${new String(serializedMap)}]".getBytes()

    when:
    def result = mapper.readValue(serializedList, new TypeReference<List<Map<String, String>>>() {})

    then:
    result == [map]
    new String(serializedList) == '[{"key1":"val1"}]'
  }

  def "test msgpack mapper serialization"() {
    setup:
    def mapper = new ObjectMapper(new MessagePackFactory())
    // GStrings get odd results in the serializer.
    def input = (1..1).collect { singletonMap("key$it".toString(), "val$it".toString()) }
    def serializedMaps = input.collect {
      mapper.writeValueAsBytes(it)
    }

    def packer = MessagePack.newDefaultBufferPacker()
    packer.packArrayHeader(serializedMaps.size())
    serializedMaps.each {
      packer.writePayload(it)
    }
    def serializedList = packer.toByteArray()

    when:
    def result = mapper.readValue(serializedList, new TypeReference<List<Map<String, String>>>() {})

    then:
    result == input
  }
}

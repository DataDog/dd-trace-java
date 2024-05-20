import InvalidProtocolBufferException
import AgentTestRunner
import DDTags
import AgentSpan
import Schema
import AvroSerializer
import GenericData
import GenericRecord
import BytesWritable

import ByteArrayOutputStream
import ByteBuffer
import Arrays
import Collections
import static TraceUtils.runUnderTrace
import static AgentTracer.activeSpan

class AvroSerializeTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  String schemaStr = "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[{\"name\":\"stringField\",\"type\":\"string\"},{\"name\":\"intField\",\"type\":\"int\"},{\"name\":\"longField\",\"type\":\"long\"},{\"name\":\"floatField\",\"type\":\"float\"},{\"name\":\"doubleField\",\"type\":\"double\"},{\"name\":\"booleanField\",\"type\":\"boolean\"},{\"name\":\"bytesField\",\"type\":\"bytes\"},{\"name\":\"arrayField\",\"type\":{\"type\":\"array\",\"items\":\"string\"}},{\"name\":\"mapField\",\"type\":{\"type\":\"map\",\"values\":\"string\"}},{\"name\":\"unionField\",\"type\":[\"null\",\"string\"]},{\"name\":\"fixedField\",\"type\":{\"name\":\"fixedField\",\"type\":\"fixed\",\"size\":4}},{\"name\":\"enumField\",\"type\":{\"name\":\"enumField\",\"type\":\"enum\",\"symbols\":[\"A\",\"B\",\"C\"]}}]}"
  Schema schema = new Parser().parse(schemaStr)


  void 'test extract avro schema on serialize & deserialize'() {
    setup:
    GenericRecord datum = new Record(schema)
    datum.put("stringField", "testString")
    datum.put("intField", 123)
    datum.put("longField", 456L)
    datum.put("floatField", 7.89f)
    datum.put("doubleField", 1.23e2)
    datum.put("booleanField", true)
    datum.put("bytesField", ByteBuffer.wrap(new byte[]{
      0x01, 0x02, 0x03
    }))
    datum.put("arrayField", Arrays.asList("a", "b", "c"))
    datum.put("mapField", Collections.singletonMap("key", "value"))
    datum.put("unionField", "unionString")
    datum.put("fixedField", new Fixed(schema.getField("fixedField").schema(), new byte[]{
      0x04, 0x05, 0x06, 0x07
    }))
    datum.put("enumField", new EnumSymbol(schema.getField("enumField").schema(), "B"))
    when:

    var bytes
    datadog.trace.agent.test.utils.TraceUtils.runUnderTrace("parent_serialize") {
      ByteArrayOutputStream out = new ByteArrayOutputStream()
      AvroSerializer<GenericRecord> serializer = new AvroSerializer<>(schema)
      BytesWritable bytesWritable = new BytesWritable()
      serializer.open(out)
      serializer.serialize(datum)
      serializer.close()

      bytes = out.toByteArray()

      System.out.println("Serialized data: " + Arrays.toString(bytes))
      throw new Exception("test")
    }
    then:
    assert bytes.size() == -12

    //    runUnderTrace("parent_deserialize") {
    //      AgentSpan span = activeSpan()
    //      span.setTag(DDTags.MANUAL_KEEP, true)
    //      Message.MyMessage.parseFrom(bytes)
    //    }
    //    TEST_WRITER.waitForTraces(2)
    //    then:
    //    assertTraces(2, SORT_TRACES_BY_ID) {
    //      trace(1) {
    //        span {
    //          hasServiceName()
    //          operationName "parent_serialize"
    //          resourceName "parent_serialize"
    //          errored false
    //          measured false
    //          tags {
    //            "$DDTags.SCHEMA_DEFINITION" schema
    //            "$DDTags.SCHEMA_WEIGHT" 1
    //            "$DDTags.SCHEMA_TYPE" "protobuf"
    //            "$DDTags.SCHEMA_NAME" "MyMessage"
    //            "$DDTags.SCHEMA_OPERATION" "serialization"
    //            "$DDTags.SCHEMA_ID" schemaID
    //            defaultTags(false)
    //          }
    //        }
    //      }
    //      trace(1) {
    //        span {
    //          hasServiceName()
    //          operationName "parent_deserialize"
    //          resourceName "parent_deserialize"
    //          errored false
    //          measured false
    //          tags {
    //            "$DDTags.SCHEMA_DEFINITION" schema
    //            "$DDTags.SCHEMA_WEIGHT" 1
    //            "$DDTags.SCHEMA_TYPE" "protobuf"
    //            "$DDTags.SCHEMA_NAME" "MyMessage"
    //            "$DDTags.SCHEMA_OPERATION" "deserialization"
    //            "$DDTags.SCHEMA_ID" schemaID
    //            defaultTags(false)
    //          }
    //        }
    //      }
  }
}
}

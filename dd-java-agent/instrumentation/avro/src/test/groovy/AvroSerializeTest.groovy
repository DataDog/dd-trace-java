import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.hadoop.io.AvroSerializer
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericData
import org.apache.avro.io.parsing.Parser
import org.apache.avro.mapred.AvroWrapper
import org.apache.hadoop.io.BytesWritable

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class AvroSerializeTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  String schemaStr = "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[{\"name\":\"stringField\",\"type\":\"string\"},{\"name\":\"intField\",\"type\":\"int\"},{\"name\":\"longField\",\"type\":\"long\"},{\"name\":\"floatField\",\"type\":\"float\"},{\"name\":\"doubleField\",\"type\":\"double\"},{\"name\":\"booleanField\",\"type\":\"boolean\"},{\"name\":\"bytesField\",\"type\":\"bytes\"},{\"name\":\"arrayField\",\"type\":{\"type\":\"array\",\"items\":\"string\"}},{\"name\":\"mapField\",\"type\":{\"type\":\"map\",\"values\":\"string\"}},{\"name\":\"unionField\",\"type\":[\"null\",\"string\"]},{\"name\":\"fixedField\",\"type\":{\"name\":\"fixedField\",\"type\":\"fixed\",\"size\":4}},{\"name\":\"enumField\",\"type\":{\"name\":\"enumField\",\"type\":\"enum\",\"symbols\":[\"A\",\"B\",\"C\"]}}]}"
  Schema schema = new Schema.Parser().parse(schemaStr)


  void 'test extract avro schema on serialize & deserialize'() {
    setup:
    GenericRecord datum = new GenericData.Record(schema)
    datum.put("stringField", "testString")
    datum.put("intField", 123)
    datum.put("longField", 456L)
    datum.put("floatField", 7.89f)
    datum.put("doubleField", 1.23e2)
    datum.put("booleanField", true)
    datum.put("bytesField", ByteBuffer.wrap(new byte[]{
      0x01, 0x02, 0x03
    }))
    datum.put("arrayField", Arrays.asList("abc", "bca", "csd"))
    datum.put("mapField", Collections.singletonMap("key", "value"))
    datum.put("unionField", "unionString")
    datum.put("fixedField", new GenericData.Fixed(schema.getField("fixedField").schema(), new byte[]{
      0x04, 0x05, 0x06, 0x07
    }))
    datum.put("enumField", new GenericData.EnumSymbol(schema.getField("enumField").schema(), "B"))
    when:

    var bytes

    runUnderTrace("parent_serialize"){

      ByteArrayOutputStream out = new ByteArrayOutputStream()
      AvroSerializer<AvroWrapper<GenericRecord>> serializer = new AvroSerializer<>(schema)
      AvroWrapper<GenericRecord> wrapper = new AvroWrapper<>(datum)
      serializer.open(out)
      serializer.serialize(wrapper)
      serializer.close()
      bytes = out.toByteArray()
    }

    then:
    System.out.println("----------Serialized data: " + Arrays.toString(bytes))

    assert bytes.size() == -12
  }
}


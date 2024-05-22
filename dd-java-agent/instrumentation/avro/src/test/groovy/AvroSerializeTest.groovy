import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.hadoop.io.AvroSerializer
import org.apache.avro.mapred.AvroWrapper

import java.nio.ByteBuffer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class AvroSerializeTest extends AgentTestRunner {



  @Override

  protected boolean isDataStreamsEnabled() {

    return true
  }
  String schemaID = "14336524272808601124"
  String openApiSchemaDef = "{\"components\":{\"schemas\":{\"TestRecord\":{\"properties\":{\"stringField\":{\"type\":\"string\"},\"intField\":{\"format\":\"int32\",\"type\":\"integer\"},\"longField\":{\"format\":\"int64\",\"type\":\"integer\"},\"floatField\":{\"format\":\"float\",\"type\":\"number\"},\"doubleField\":{\"format\":\"double\",\"type\":\"number\"},\"booleanField\":{\"type\":\"boolean\"},\"bytesField\":{\"format\":\"byte\",\"type\":\"string\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
  String schemaStr = '''{

        "type":"record",

        "name":"TestRecord",

        "fields":[

            {"name":"stringField","type":"string"},

            {"name":"intField","type":"int"},

            {"name":"longField","type":"long"},

            {"name":"floatField","type":"float"},

            {"name":"doubleField","type":"double"},

            {"name":"booleanField","type":"boolean"},

            {"name":"bytesField","type":"bytes"}

        ]

    }'''



  Schema schemaDef = new Schema.Parser().parse(schemaStr)



  void 'test extract avro schema on serialize & deserialize'() {

    setup:

    GenericRecord datum = new GenericData.Record(schemaDef)

    datum.put("stringField", "testString")

    datum.put("intField", 123)

    datum.put("longField", 456L)

    datum.put("floatField", 7.89f)

    datum.put("doubleField", 1.23e2)

    datum.put("booleanField", true)

    datum.put("bytesField", ByteBuffer.wrap(new byte[]{
      0x01, 0x02, 0x03
    }))



    when:

    def bytes

    runUnderTrace("parent_serialize") {

      ByteArrayOutputStream out = new ByteArrayOutputStream()

      AvroSerializer<AvroWrapper<GenericRecord>> serializer = new AvroSerializer<>(schemaDef)

      AvroWrapper<GenericRecord> wrapper = new AvroWrapper<>(datum)

      serializer.open(out)

      serializer.serialize(wrapper)

      serializer.close()

      bytes = out.toByteArray()
      println(activeSpan().getTag(DDTags.SCHEMA_ID)+"]]]]]")
    }

    TEST_WRITER.waitForTraces(1)


    then:

    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(1) {
        span {
          hasServiceName()
          operationName "parent_serialize"
          resourceName "parent_serialize"
          errored false
          measured false
          tags {
            "$DDTags.SCHEMA_DEFINITION" openApiSchemaDef
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "avro"
            "$DDTags.SCHEMA_NAME" "TestRecord"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" schemaID
            defaultTags(false)
          }
        }
      }
    }
  }
}
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import org.apache.avro.Schema
import org.apache.avro.io.DatumReader
import org.apache.avro.specific.SpecificDatumReader


import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class AvroDatumReaderTest extends AgentTestRunner {



  @Override

  protected boolean isDataStreamsEnabled() {

    return true
  }
  String schemaID = "5493435211744749109"
  String openApiSchemaDef = "{\"components\":{\"schemas\":{\"TestRecord\":{\"properties\":{\"stringField\":{\"type\":\"string\"},\"intField\":{\"format\":\"int32\",\"type\":\"integer\"},\"longField\":{\"format\":\"int64\",\"type\":\"integer\"},\"floatField\":{\"format\":\"float\",\"type\":\"number\"},\"doubleField\":{\"format\":\"double\",\"type\":\"number\"},\"booleanField\":{\"type\":\"boolean\"},\"bytesField\":{\"format\":\"byte\",\"type\":\"string\"},\"nullField\":{\"type\":\"null\"},\"enumField\":{\"enum\":[\"A\",\"B\",\"C\"],\"type\":\"string\"},\"fixedField\":{\"type\":\"string\"},\"recordField\":{\"type\":\"object\"},\"arrayField\":{\"items\":{\"type\":\"integer\"},\"type\":\"array\"},\"mapField\":{\"description\":\"Map type\",\"type\":\"object\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
  String schemaStr = '''
    {
      "type": "record",
      "name": "TestRecord",
      "fields": [
        {"name": "stringField", "type": "string"},
        {"name": "intField", "type": "int"},
        {"name": "longField", "type": "long"},
        {"name": "floatField", "type": "float"},
        {"name": "doubleField", "type": "double"},
        {"name": "booleanField", "type": "boolean"},
        {"name": "bytesField", "type": "bytes"},
        {"name": "nullField", "type": "null"},
        {"name": "enumField", "type": {"type": "enum", "name": "TestEnum", "symbols": ["A", "B", "C"]}},
        {"name": "fixedField", "type": {"type": "fixed", "name": "TestFixed", "size": 16}},
        {"name": "recordField", "type": {"type": "record", "name": "NestedRecord", "fields": [{"name": "nestedString", "type": "string"}]}},
        {"name": "arrayField", "type": {"type": "array", "items": "int"}},
        {"name": "mapField", "type": {"type": "map", "values": "string"}}
      ]
    }
    '''
  Schema schemaDef = new Schema.Parser().parse(schemaStr)



  void 'test extract avro schema on serialize'() {

    setup:
    DatumReader datumReader = new SpecificDatumReader()
    when:
    runUnderTrace("parent_serialize") {
      datumReader.setSchema(schemaDef)
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
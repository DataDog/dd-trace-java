import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.BinaryDecoder
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import org.apache.avro.io.DecoderFactory

import org.apache.avro.specific.SpecificDatumWriter

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import java.nio.ByteBuffer
class AvroDatumReaderTest extends InstrumentationSpecification {



  @Override

  protected boolean isDataStreamsEnabled() {

    return true
  }
  String schemaID = "8924443781494069161"
  String openApiSchemaDef = "{\"components\":{\"schemas\":{\"TestRecord\":{\"properties\":{\"stringField\":{\"type\":\"string\"},\"intField\":{\"format\":\"int32\",\"type\":\"integer\"},\"longField\":{\"format\":\"int64\",\"type\":\"integer\"},\"floatField\":{\"format\":\"float\",\"type\":\"number\"},\"doubleField\":{\"format\":\"double\",\"type\":\"number\"},\"booleanField\":{\"type\":\"boolean\"},\"bytesField\":{\"format\":\"byte\",\"type\":\"string\"},\"nullField\":{\"type\":\"null\"},\"enumField\":{\"enum\":[\"A\",\"B\",\"C\"],\"type\":\"string\"},\"fixedField\":{\"type\":\"string\"},\"recordField\":{\"type\":\"#/components/schemas/NestedRecord\"},\"arrayField\":{\"items\":{\"type\":\"integer\"},\"type\":\"array\"},\"mapField\":{\"description\":\"Map type with string keys and string values\",\"type\":\"object\"},\"arrayNestedField\":{\"items\":{\"type\":\"#/components/schemas/OtherNestedRecord\"},\"type\":\"array\"},\"mapNestedField\":{\"description\":\"Map type with string keys and #/components/schemas/ThirdTypeOfNestedRecord values\",\"type\":\"object\"}},\"type\":\"object\"},\"NestedRecord\":{\"properties\":{\"nestedString\":{\"type\":\"string\"}},\"type\":\"object\"},\"OtherNestedRecord\":{\"properties\":{\"nestedString\":{\"type\":\"string\"}},\"type\":\"object\"},\"ThirdTypeOfNestedRecord\":{\"properties\":{\"nestedString\":{\"type\":\"string\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
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
        {"name": "mapField", "type": {"type": "map", "values": "string"}},
        {"name": "arrayNestedField", "type": { "type": "array", "items": {"type": "record", "name": "OtherNestedRecord", "fields": [{"name": "nestedString", "type": "string"}]}}},
        {"name": "mapNestedField", "type": {"type": "map", "values": {"type": "record", "name": "ThirdTypeOfNestedRecord", "fields": [{"name": "nestedString", "type": "string"}]}}}
      ]
    }
    '''
  Schema schemaDef = new Schema.Parser().parse(schemaStr)

  void 'test extract avro schema on deserialize'() {

    setup:
    // Creating the datum record
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
    datum.put("nullField", null)
    datum.put("enumField", new GenericData.EnumSymbol(schemaDef.getField("enumField").schema(), "A"))
    datum.put("fixedField", new GenericData.Fixed(schemaDef.getField("fixedField").schema(), new byte[16]))

    // Nested record field
    GenericRecord nestedRecord = new GenericData.Record(schemaDef.getField("recordField").schema())
    nestedRecord.put("nestedString", "nestedTestString")
    datum.put("recordField", nestedRecord)

    // Array field
    datum.put("arrayField", Arrays.asList(1, 2, 3))

    // Map field
    Map<String, String> map = new HashMap<>()
    map.put("key1", "value1")
    map.put("key2", "value2")
    datum.put("mapField", map)

    // array of nested fields
    GenericRecord nestedRecordA = new GenericData.Record(schemaDef.getField("arrayNestedField").schema().getElementType())
    nestedRecordA.put("nestedString", "a")
    GenericRecord nestedRecordB = new GenericData.Record(schemaDef.getField("arrayNestedField").schema().getElementType())
    nestedRecordB.put("nestedString", "b")
    datum.put("arrayNestedField", Arrays.asList(nestedRecordA, nestedRecordB))

    // map of nested fields
    Map<String, GenericRecord> nestedMap = new HashMap<>()
    GenericRecord nestedRecordC = new GenericData.Record(schemaDef.getField("mapNestedField").schema().getValueType())
    nestedRecordC.put("nestedString", "a")
    nestedMap.put("key1", nestedRecordC)
    datum.put("mapNestedField", nestedMap)

    when:
    def bytes
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    Encoder encoder = EncoderFactory.get().binaryEncoder(out, null)
    SpecificDatumWriter<GenericRecord> datumWriter = new SpecificDatumWriter<>(schemaDef)
    datumWriter.write(datum, encoder)
    encoder.flush()
    bytes = out.toByteArray()

    GenericRecord result = runUnderTrace("parent_deserialize") {
      ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)
      BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(inputStream, null)
      GenericDatumReader<GenericRecord> datumReader = new GenericDatumReader<>(schemaDef)

      datumReader.read(null, decoder)
    }

    TEST_WRITER.waitForTraces(1)

    then:
    result != null

    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(1) {
        span {
          hasServiceName()
          operationName "parent_deserialize"
          resourceName "parent_deserialize"
          errored false
          measured false
          tags {
            "$DDTags.SCHEMA_DEFINITION" openApiSchemaDef
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "avro"
            "$DDTags.SCHEMA_NAME" "TestRecord"
            "$DDTags.SCHEMA_OPERATION" "deserialization"
            "$DDTags.SCHEMA_ID" schemaID
            defaultTags(false)
          }
        }
      }
    }
  }
  void 'test extract avro schema on serialize'() {

    setup:
    // Creating the datum record
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
    datum.put("nullField", null)
    datum.put("enumField", new GenericData.EnumSymbol(schemaDef.getField("enumField").schema(), "A"))
    datum.put("fixedField", new GenericData.Fixed(schemaDef.getField("fixedField").schema(), new byte[16]))

    // Nested record field
    GenericRecord nestedRecord = new GenericData.Record(schemaDef.getField("recordField").schema())
    nestedRecord.put("nestedString", "nestedTestString")
    datum.put("recordField", nestedRecord)

    // Array field
    datum.put("arrayField", Arrays.asList(1, 2, 3))

    // Map field
    Map<String, String> map = new HashMap<>()
    map.put("key1", "value1")
    map.put("key2", "value2")
    datum.put("mapField", map)

    // array of nested fields
    GenericRecord nestedRecordA = new GenericData.Record(schemaDef.getField("arrayNestedField").schema().getElementType())
    nestedRecordA.put("nestedString", "a")
    GenericRecord nestedRecordB = new GenericData.Record(schemaDef.getField("arrayNestedField").schema().getElementType())
    nestedRecordB.put("nestedString", "b")
    datum.put("arrayNestedField", Arrays.asList(nestedRecordA, nestedRecordB))

    // map of nested fields
    Map<String, GenericRecord> nestedMap = new HashMap<>()
    GenericRecord nestedRecordC = new GenericData.Record(schemaDef.getField("mapNestedField").schema().getValueType())
    nestedRecordC.put("nestedString", "a")
    nestedMap.put("key1", nestedRecordC)
    datum.put("mapNestedField", nestedMap)

    when:
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    Encoder encoder = EncoderFactory.get().binaryEncoder(out, null)
    SpecificDatumWriter<GenericRecord> datumWriter = new SpecificDatumWriter<>(schemaDef)


    def bytes = runUnderTrace("parent_serialize") {
      datumWriter.write(datum, encoder)
      encoder.flush()
      out.toByteArray()
    }

    TEST_WRITER.waitForTraces(1)

    then:
    bytes != null

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

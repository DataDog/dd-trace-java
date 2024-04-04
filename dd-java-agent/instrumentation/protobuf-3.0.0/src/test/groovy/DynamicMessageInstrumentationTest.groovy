import com.google.protobuf.DynamicMessage
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class DynamicMessageInstrumentationTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  void 'test extract protobuf schema using the dynamic message'() {
    setup:
    Message.MyMessage message = Message.MyMessage.newBuilder()
      .setId("1")
      .setValue("Hello from Protobuf!")
      .build()
    when:
    String schema = "{\"components\":{\"schemas\":{\"MyMessage\":{\"properties\":{\"id\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"other_message\":{\"items\":{\"\$ref\":\"#/components/schemas/OtherMessage\"},\"type\":\"array\"}},\"type\":\"object\"},\"OtherMessage\":{\"properties\":{\"name\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"age\":{\"format\":\"int32\",\"type\":\"integer\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
    String schemaID = "17871055810055148870"
    var bytes
    runUnderTrace("parent_serialize") {
      bytes = message.toByteArray()
    }
    runUnderTrace("parent_deserialize") {
      DynamicMessage.parseFrom(Message.MyMessage.getDescriptor(), bytes)
    }
    TEST_WRITER.waitForTraces(2)
    then:
    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(2) {
        basicSpan(it, "parent_serialize")
        span {
          hasServiceName()
          operationName "serialize"
          resourceName "serialize"
          errored false
          measured false
          childOf span(0)
          tags {
            "$DDTags.SCHEMA_DEFINITION" schema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "MyMessage"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" schemaID
            defaultTags(false)
          }
        }
      }
      trace(2) {
        basicSpan(it, "parent_deserialize")
        span {
          hasServiceName()
          operationName "deserialize"
          resourceName "deserialize"
          errored false
          measured false
          childOf span(0)
          tags {
            "$DDTags.SCHEMA_DEFINITION" schema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "MyMessage"
            "$DDTags.SCHEMA_OPERATION" "deserialization"
            "$DDTags.SCHEMA_ID" schemaID
            defaultTags(false)
          }
        }
      }
    }
  }
}

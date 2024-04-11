import com.google.protobuf.InvalidProtocolBufferException
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class AbstractMessageInstrumentationTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  String schema = "{\"components\":{\"schemas\":{\"MyMessage\":{\"properties\":{\"id\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"other_message\":{\"items\":{\"\$ref\":\"#/components/schemas/OtherMessage\"},\"type\":\"array\"}},\"type\":\"object\"},\"OtherMessage\":{\"properties\":{\"name\":{\"items\":{\"type\":\"string\"},\"type\":\"array\"},\"age\":{\"format\":\"int32\",\"type\":\"integer\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
  String schemaID = "17871055810055148870"

  void 'test extract protobuf schema on serialize & deserialize'() {
    setup:
    Message.MyMessage message = Message.MyMessage.newBuilder()
    .setId("1")
    .setValue("Hello from Protobuf!")
    .build()
    when:
    var bytes
    runUnderTrace("parent_serialize") {
      bytes = message.toByteArray()
    }
    runUnderTrace("parent_deserialize") {
      Message.MyMessage.parseFrom(bytes)
    }
    TEST_WRITER.waitForTraces(2)
    then:
    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(2) {
        basicSpan(it, "parent_serialize")
        span {
          hasServiceName()
          operationName "protobuf.serialize"
          resourceName "protobuf.serialize"
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
          operationName "protobuf.deserialize"
          resourceName "protobuf.deserialize"
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

  void 'test error when de-serializing'() {
    setup:
    Message.MyMessage message = Message.MyMessage.newBuilder()
    .setId("1")
    .setValue("Hello from Protobuf!")
    .build()
    when:
    runUnderTrace("parent_deserialize") {
      try {
        Message.MyMessage.parseFrom(new byte[]{
          1, 2, 3, 4, 5
        })
      } catch (InvalidProtocolBufferException e) {
      }
      blockUntilChildSpansFinished(1)
    }
    then:
    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(2) {
        basicSpan(it, "parent_deserialize")
        span {
          hasServiceName()
          operationName "protobuf.deserialize"
          resourceName "protobuf.deserialize"
          errored true
          measured false
          childOf span(0)
          tags {
            "$DDTags.ERROR_MSG" "Protocol message contained an invalid tag (zero)."
            "$DDTags.ERROR_TYPE" "com.google.protobuf.InvalidProtocolBufferException"
            "$DDTags.ERROR_STACK" String
            defaultTags(false)
          }
        }
      }
    }
  }
}

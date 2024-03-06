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

  String schema = "{\"openapi\": \"3.0.0\",\"components\": {\"schemas\": {\"OtherMessage\": {\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"array\", \"items\":{\"type\": \"string\"}}, \"age\": {\"type\": \"integer\", \"format\": \"int32\"}}}, \"MyMessage\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"string\"}, \"value\": {\"type\": \"string\"}, \"other_message\": {\"type\": \"array\", \"items\":{ \"\$ref\": \"#/components/schemas/OtherMessage\" }}}}}}}"
  String schemaID = "4334562698863338724"

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
      blockUntilChildSpansFinished(1)
    }
    runUnderTrace("parent_deserialize") {
      Message.MyMessage.parseFrom(bytes)
      blockUntilChildSpansFinished(1)
    }
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
          operationName "deserialize"
          resourceName "deserialize"
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

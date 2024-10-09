package com.datadog.instrumentation.protobuf

import com.google.protobuf.InvalidProtocolBufferException
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import com.datadog.instrumentation.protobuf.generated.Message.MyMessage
import com.datadog.instrumentation.protobuf.generated.Message.OtherMessage

class AbstractMessageInstrumentationTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  String schema = "{\"components\":{\"schemas\":{\"com.datadog.instrumentation.protobuf.generated.MyMessage\":{\"properties\":{\"id\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"other_message\":{\"items\":{\"\$ref\":\"#/components/schemas/com.datadog.instrumentation.protobuf.generated.OtherMessage\"},\"type\":\"array\"}},\"type\":\"object\"},\"com.datadog.instrumentation.protobuf.generated.OtherMessage\":{\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"format\":\"int32\",\"type\":\"integer\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
  String schemaID = "9054678588020233022"


  void 'test extract protobuf schema on serialize & deserialize'() {
    setup:
    MyMessage message = MyMessage.newBuilder()
    .setId("1")
    .setValue("Hello from Protobuf!")
    .setNested(OtherMessage.newBuilder().setName("hello").setAge(10).build())
    .build()
    when:
    var bytes
    runUnderTrace("parent_serialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      bytes = message.toByteArray()
    }
    runUnderTrace("parent_deserialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      MyMessage.parseFrom(bytes)
    }
    TEST_WRITER.waitForTraces(2)
    then:
    assertTraces(2, SORT_TRACES_BY_ID) {
      trace(1) {
        span {
          hasServiceName()
          operationName "parent_serialize"
          resourceName "parent_serialize"
          errored false
          measured false
          tags {
            "$DDTags.SCHEMA_DEFINITION" schema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.MyMessage"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" schemaID
            defaultTags(false)
          }
        }
      }
      trace(1) {
        span {
          hasServiceName()
          operationName "parent_deserialize"
          resourceName "parent_deserialize"
          errored false
          measured false
          tags {
            "$DDTags.SCHEMA_DEFINITION" schema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.MyMessage"
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
    MyMessage message = MyMessage.newBuilder()
    .setId("1")
    .setValue("Hello from Protobuf!")
    .build()
    when:
    runUnderTrace("parent_deserialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      try {
        MyMessage.parseFrom(new byte[]{
          1, 2, 3, 4, 5
        })
      } catch (InvalidProtocolBufferException e) {
      }
    }
    TEST_WRITER.waitForTraces(1)
    then:
    assertTraces(1, SORT_TRACES_BY_ID) {
      trace(1) {
        span {
          hasServiceName()
          operationName "parent_deserialize"
          resourceName "parent_deserialize"
          errored true
          measured false
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

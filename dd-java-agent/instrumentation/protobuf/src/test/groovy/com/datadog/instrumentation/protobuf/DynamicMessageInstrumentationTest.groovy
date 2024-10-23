package com.datadog.instrumentation.protobuf

import com.google.protobuf.DynamicMessage
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import com.datadog.instrumentation.protobuf.generated.Message.MyMessage

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class DynamicMessageInstrumentationTest extends AgentTestRunner {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  void 'test extract protobuf schema using the dynamic message'() {
    setup:
    MyMessage message = MyMessage.newBuilder()
      .setId("1")
      .setValue("Hello from Protobuf!")
      .build()
    when:
    String schema = "{\"components\":{\"schemas\":{\"com.datadog.instrumentation.protobuf.generated.MyMessage\":{\"properties\":{\"id\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"},\"other_message\":{\"items\":{\"\$ref\":\"#/components/schemas/com.datadog.instrumentation.protobuf.generated.OtherMessage\"},\"type\":\"array\"}},\"type\":\"object\"},\"com.datadog.instrumentation.protobuf.generated.OtherMessage\":{\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"format\":\"int32\",\"type\":\"integer\"}},\"type\":\"object\"}}},\"openapi\":\"3.0.0\"}"
    String schemaID = "9054678588020233022"
    var bytes
    runUnderTrace("parent_serialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      bytes = message.toByteArray()
    }
    runUnderTrace("parent_deserialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      DynamicMessage.parseFrom(MyMessage.getDescriptor(), bytes)
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
}

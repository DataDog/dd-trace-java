package com.datadog.instrumentation.protobuf

import com.datadog.instrumentation.protobuf.generated.Message.MyMessage
import com.datadog.instrumentation.protobuf.generated.Message.OtherMessage
import com.datadog.instrumentation.protobuf.generated.Message.RecursiveMessage
import com.google.protobuf.InvalidProtocolBufferException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class AbstractMessageInstrumentationTest extends InstrumentationSpecification {
  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }

  void "test extract protobuf schema on serialize & deserialize"() {

    String expectedSchema = """{
         "components":{
            "schemas":{
               "com.datadog.instrumentation.protobuf.generated.MyMessage":{
                  "properties":{
                     "id":{
                        "extensions":{
                           "x-protobuf-number":"1"
                        },
                        "type":"string"
                     },
                     "value":{
                        "extensions":{
                           "x-protobuf-number":"2"
                        },
                        "type":"string"
                     },
                     "other_message":{
                        "extensions":{
                           "x-protobuf-number":"3"
                        },
                        "items":{
                           "\$ref":"#/components/schemas/com.datadog.instrumentation.protobuf.generated.OtherMessage"
                        },
                        "type":"array"
                     },
                     "nested":{
                        "\$ref":"#/components/schemas/com.datadog.instrumentation.protobuf.generated.OtherMessage",
                        "extensions":{
                           "x-protobuf-number":"4"
                        }
                     }
                  },
                  "type":"object"
               },
               "com.datadog.instrumentation.protobuf.generated.OtherMessage":{
                  "properties":{
                     "name":{
                        "extensions":{
                           "x-protobuf-number":"1"
                        },
                        "type":"string"
                     },
                     "age":{
                        "extensions":{
                           "x-protobuf-number":"2"
                        },
                        "format":"int32",
                        "type":"integer"
                     }
                  },
                  "type":"object"
               }
            }
         },
         "openapi":"3.0.0"
      }"""
    expectedSchema = expectedSchema.replaceAll("[ \n]", "") // the spaces are just here to make it readable
    String expectedSchemaID = "2792908287829424040"

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
            "$DDTags.SCHEMA_DEFINITION" expectedSchema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.MyMessage"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" expectedSchemaID
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
            "$DDTags.SCHEMA_DEFINITION" expectedSchema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.MyMessage"
            "$DDTags.SCHEMA_OPERATION" "deserialization"
            "$DDTags.SCHEMA_ID" expectedSchemaID
            defaultTags(false)
          }
        }
      }
    }
  }

  void "test extract protobuf schema with recursive message"() {
    String expectedSchema = """{
         "components":{
            "schemas":{
               "com.datadog.instrumentation.protobuf.generated.RecursiveMessage":{
                  "properties":{
                     "value":{
                        "extensions":{
                           "x-protobuf-number":"1"
                        },
                        "format":"int32",
                        "type":"integer"
                     },
                     "next":{
                        "\$ref":"#/components/schemas/com.datadog.instrumentation.protobuf.generated.RecursiveMessage",
                        "extensions":{
                           "x-protobuf-number":"2"
                        }
                     }
                  },
                  "type":"object"
               }
            }
         },
         "openapi":"3.0.0"
      }"""
    expectedSchema = expectedSchema.replaceAll("[ \n]", "") // the spaces are just here to make it readable
    String expectedSchemaID = "8377547842972884891"

    setup:
    getTEST_DATA_STREAMS_MONITORING()
    RecursiveMessage message = RecursiveMessage.newBuilder()
      .setValue(12)
      .build()
    when:
    byte[] bytes
    runUnderTrace("parent_serialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      bytes = message.toByteArray()
    }
    runUnderTrace("parent_deserialize") {
      AgentSpan span = activeSpan()
      span.setTag(DDTags.MANUAL_KEEP, true)
      RecursiveMessage.parseFrom(bytes)
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
            "$DDTags.SCHEMA_DEFINITION" expectedSchema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.RecursiveMessage"
            "$DDTags.SCHEMA_OPERATION" "serialization"
            "$DDTags.SCHEMA_ID" expectedSchemaID
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
            "$DDTags.SCHEMA_DEFINITION" expectedSchema
            "$DDTags.SCHEMA_WEIGHT" 1
            "$DDTags.SCHEMA_TYPE" "protobuf"
            "$DDTags.SCHEMA_NAME" "com.datadog.instrumentation.protobuf.generated.RecursiveMessage"
            "$DDTags.SCHEMA_OPERATION" "deserialization"
            "$DDTags.SCHEMA_ID" expectedSchemaID
            defaultTags(false)
          }
        }
      }
    }
  }

  void "test error when de-serializing"() {
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
      } catch (InvalidProtocolBufferException _) {
      }
    }
    TEST_WRITER.waitForTraces(1)

    then:
    message != null

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

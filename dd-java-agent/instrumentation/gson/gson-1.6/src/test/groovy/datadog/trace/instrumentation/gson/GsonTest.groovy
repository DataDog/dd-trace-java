package datadog.trace.instrumentation.gson

import com.google.gson.Gson
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

class GsonTest extends InstrumentationSpecification {

  static class TestData {
    String name
    int value
  }

  def "test toJson creates span"() {
    setup:
    def gson = new Gson()
    def data = [name: "test", value: 123]

    when:
    def json = gson.toJson(data)

    then:
    json == '{"name":"test","value":123}'
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          resourceName "gson.toJson"
          spanType "json"
          tags {
            "$Tags.COMPONENT" "gson"
            defaultTags()
          }
        }
      }
    }
  }

  def "test fromJson creates span"() {
    setup:
    def gson = new Gson()
    def json = '{"name":"test","value":123}'

    when:
    def result = gson.fromJson(json, TestData.class)

    then:
    result.name == "test"
    result.value == 123
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.fromJson"
          resourceName "gson.fromJson"
          spanType "json"
          tags {
            "$Tags.COMPONENT" "gson"
            defaultTags()
          }
        }
      }
    }
  }

  def "test nested toJson calls"() {
    setup:
    def gson = new Gson()
    def data = [
      name: "outer",
      nested: [name: "inner", value: 456]
    ]

    when:
    def json = gson.toJson(data)

    then:
    json != null
    // Should only create one span even with nested serialization
    assertTraces(1) {
      trace(1) {
        span {
          operationName "gson.toJson"
          resourceName "gson.toJson"
          spanType "json"
          tags {
            "$Tags.COMPONENT" "gson"
            defaultTags()
          }
        }
      }
    }
  }
}

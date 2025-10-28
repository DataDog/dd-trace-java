import annotatedsample.TracedMethods
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

class SpanAttributeAnnotationTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
  }

  def "test SpanAttribute annotated #type parameter"() {
    setup:
    def methodName = "sayHelloWith${typeName}Attribute"
    TracedMethods."$methodName"(value)

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.$methodName"
          operationName "TracedMethods.$methodName"
          parent()
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
            "custom-tag" value
          }
        }
      }
    }

    where:
    type     | value
    'String' | 'value'
    'int'    | 12
    'long'   | 23456L
    'list'   | ['value1', 'value2', 'value3']
    typeName = type.substring(0, 1).toUpperCase() + type.substring(1)
  }

  def "test multiple SpanAttribute"() {
    setup:
    def methodName = "sayHelloWithMultipleAttributes"
    TracedMethods."$methodName"("param1", "param2")

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.$methodName"
          operationName "TracedMethods.$methodName"
          parent()
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
            "custom-tag1" "param1"
            "custom-tag2" "param2"
          }
        }
      }
    }
  }
}


import annotatedsample.AnnotatedMethods
import datadog.trace.agent.test.InstrumentationSpecification

class AddingSpanAttributesAnnotationTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.26.enabled", "true")
  }

  def "test AddingSpanAttributes annotated method with #type annotated parameter"() {
    setup:
    def methodName = "sayHelloWith${typeName}Attribute"
    def testSpan = TEST_TRACER.startSpan("test", "operation")
    def scope = TEST_TRACER.activateManualSpan(testSpan)
    AnnotatedMethods."$methodName"(value)
    scope.close()
    testSpan.finish()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "operation"
          operationName "operation"
          parent()
          errored false
          tags {
            defaultTags()
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

  def "test AddingSpanAttributes annotated method with multiple annotated parameters"() {
    setup:
    def methodName = "sayHelloWithMultipleAttributes"
    def testSpan = TEST_TRACER.startSpan("test", "operation")
    def scope = TEST_TRACER.activateManualSpan(testSpan)
    AnnotatedMethods."$methodName"("param1", "param2")
    scope.close()
    testSpan.finish()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "operation"
          operationName "operation"
          parent()
          errored false
          tags {
            defaultTags()
            "custom-tag1" "param1"
            "custom-tag2" "param2"
          }
        }
      }
    }
  }

  def "test AddingSpanAttributes annotated method is skipped if no active span"() {
    setup:
    def testSpan = TEST_TRACER.startSpan("test", "operation")
    AnnotatedMethods.sayHelloWithStringAttribute('hello')
    testSpan.finish()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "operation"
          operationName "operation"
          parent()
          errored false
          tags {
            defaultTags()
            "custom-tag" null
          }
        }
      }
    }
  }
}

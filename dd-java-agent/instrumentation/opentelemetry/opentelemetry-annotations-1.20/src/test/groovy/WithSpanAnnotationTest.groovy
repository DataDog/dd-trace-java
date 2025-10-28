import annotatedsample.TracedMethods
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.CountDownLatch

class WithSpanAnnotationTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
    injectSysConfig("dd.measure.methods", "${TracedMethods.name}[sayHelloMeasured]")
  }

  def "test WithSpan annotated method"() {
    setup:
    TracedMethods.sayHello()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "custom-service-name"
          resourceName "TracedMethods.sayHello"
          operationName "TracedMethods.sayHello"
          parent()
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated method with custom value"() {
    setup:
    TracedMethods.sayHelloWithCustomOperationName()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "custom-service-name"
          resourceName "custom-operation-name"
          operationName "custom-operation-name"
          parent()
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated method with #kind kind"() {
    setup:
    def methodName = "sayHelloWith${kindName}Kind"
    TracedMethods."$methodName"()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.${methodName}"
          operationName "TracedMethods.${methodName}"
          parent()
          spanType(type)
          errored false
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" kindTag
          }
        }
      }
    }

    where:
    kind       | type                         | kindTag
    'SERVER'   | DDSpanTypes.HTTP_SERVER      | Tags.SPAN_KIND_SERVER
    'CLIENT'   | DDSpanTypes.HTTP_CLIENT      | Tags.SPAN_KIND_CLIENT
    'PRODUCER' | DDSpanTypes.MESSAGE_PRODUCER | Tags.SPAN_KIND_PRODUCER
    'CONSUMER' | DDSpanTypes.MESSAGE_CONSUMER | Tags.SPAN_KIND_CONSUMER
    'INTERNAL' | null                         | Tags.SPAN_KIND_INTERNAL
    kindName = kind.substring(0, 1) + kind.substring(1).toLowerCase()
  }

  def "test WithSpan annotated method throwing exception"() {
    setup:
    Throwable error = null
    try {
      TracedMethods.throwException()
    } catch (final Throwable ex) {
      error = ex
    }

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.throwException"
          operationName "TracedMethods.throwException"
          errored true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
            errorTags(error.class, error.getMessage())
          }
        }
      }
    }
  }

  def "test WithSpan annotated anonymous inner method"() {
    setup:
    TracedMethods.traceAnonymousInnerClass()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods\$1.call"
          operationName "TracedMethods\$1.call"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (CompletableFuture)"() {
    setup:
    def latch = new CountDownLatch(1)
    def completableFuture = TracedMethods.traceAsyncCompletableFuture(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    completableFuture.join()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.traceAsyncCompletableFuture"
          operationName "TracedMethods.traceAsyncCompletableFuture"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing CompletableFuture)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new RuntimeException("Test exception")
    def completableFuture = TracedMethods.traceAsyncFailingCompletableFuture(latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    completableFuture.join()

    then:
    thrown(RuntimeException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.traceAsyncFailingCompletableFuture"
          operationName "TracedMethods.traceAsyncFailingCompletableFuture"
          errored true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
            errorTags(expectedException)
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (CompletionStage)"() {
    setup:
    def latch = new CountDownLatch(1)
    def completionStage = TracedMethods.traceAsyncCompletionStage(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    completionStage.toCompletableFuture().join()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.traceAsyncCompletionStage"
          operationName "TracedMethods.traceAsyncCompletionStage"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing CompletionStage)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new RuntimeException("Test exception")
    def completionStage = TracedMethods.traceAsyncFailingCompletionStage(latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    completionStage.toCompletableFuture().join()

    then:
    thrown(RuntimeException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.traceAsyncFailingCompletionStage"
          operationName "TracedMethods.traceAsyncFailingCompletionStage"
          errored true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
            errorTags(expectedException)
          }
        }
      }
    }
  }

  def "test WithSpan annotated measured method"() {
    setup:
    TracedMethods.sayHelloMeasured()

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "TracedMethods.sayHelloMeasured"
          operationName "TracedMethods.sayHelloMeasured"
          parent()
          errored false
          measured true
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }
}

import annotatedsample.RxJava2TracedMethods
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.CountDownLatch

class RxJava2ResultExtensionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.otel.enabled", "true")
    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
  }

  def "test WithSpan annotated async method #type"() {
    setup:
    def latch = new CountDownLatch(1)
    def asyncType = RxJava2TracedMethods."$method"(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    asyncType."$operation"()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "RxJava2TracedMethods.$method"
          operationName "RxJava2TracedMethods.traceAsync$type"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }

    where:
    type          | operation
    'Completable' | 'blockingGet'
    'Maybe'       | 'blockingGet'
    'Single'      | 'blockingGet'
    'Observable'  | 'blockingLast'
    'Flowable'    | 'blockingLast'
    method = "traceAsync$type"
  }

  def "test WithSpan annotated async method failing #type"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new IllegalStateException("Test exception")
    def asyncType = RxJava2TracedMethods."$method"(latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    asyncType."$operation"()

    then:
    thrown(IllegalStateException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "RxJava2TracedMethods.$method"
          operationName "RxJava2TracedMethods.$method"
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

    where:
    type          | operation
    'Completable' | 'blockingAwait'
    'Maybe'       | 'blockingGet'
    'Single'      | 'blockingGet'
    'Observable'  | 'blockingLast'
    'Flowable'    | 'blockingLast'
    method = "traceAsyncFailing$type"
  }

  def "test WithSpan annotated async method cancelled #type"() {
    setup:
    def latch = new CountDownLatch(1)
    def asyncType = RxJava2TracedMethods."$method"(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    asyncType.subscribe().dispose()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "RxJava2TracedMethods.$method"
          operationName "RxJava2TracedMethods.traceAsync$type"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }

    where:
    type          | operation
    'Completable' | 'blockingGet'
    'Maybe'       | 'blockingGet'
    'Single'      | 'blockingGet'
    'Observable'  | 'blockingLast'
    'Flowable'    | 'blockingLast'
    method = "traceAsync$type"
  }
}

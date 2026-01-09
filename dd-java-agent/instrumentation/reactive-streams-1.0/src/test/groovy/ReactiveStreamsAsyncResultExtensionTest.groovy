import annotatedsample.ReactiveStreamsTracedMethods
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.CountDownLatch

class ReactiveStreamsAsyncResultExtensionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.otel.enabled", "true")
  }

  def "test WithSpan annotated async method (Publisher)"() {
    setup:
    def latch = new CountDownLatch(1)
    def publisher = ReactiveStreamsTracedMethods.traceAsyncPublisher(latch)
    def subscriber = new ReactiveStreamsTracedMethods.ConsumerSubscriber<String>()

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing Publisher)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new IllegalStateException("Test exception")
    def publisher = ReactiveStreamsTracedMethods.traceAsyncFailingPublisher(latch, expectedException)
    def subscriber = new ReactiveStreamsTracedMethods.ConsumerSubscriber<String>()

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncFailingPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncFailingPublisher"
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

  def "test WithSpan annotated async method (cancelled Publisher)"() {
    setup:
    def latch = new CountDownLatch(1)
    def publisher = ReactiveStreamsTracedMethods.traceAsyncPublisher(latch)
    def subscriber = new ReactiveStreamsTracedMethods.CancellerSubscriber<String>()

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test nested WithSpan annotated async method "() {
    setup:
    def latch = new CountDownLatch(1)
    def publisher = ReactiveStreamsTracedMethods.traceNestedAsyncPublisher(latch)
    def subscriber = new ReactiveStreamsTracedMethods.ConsumerSubscriber<String>()

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(2) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceNestedAsyncPublisher"
          operationName "ReactiveStreamsTracedMethods.traceNestedAsyncPublisher"
          parent()
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          childOfPrevious()
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

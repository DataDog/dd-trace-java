import annotatedsample.ReactiveStreamsTracedMethods
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.CountDownLatch

class ReactiveStreamsAsyncResultSupportExtensionTest extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
    injectSysConfig("dd.integration.reactive-streams-1.enabled", "true")
  }

  def "test WithSpan annotated async method (Publisher)"() {
    setup:
    def latch = new CountDownLatch(1)
    def publisher = ReactiveStreamsTracedMethods.traceAsyncPublisher(latch)
    def subscriber = new ReactiveStreamsTracedMethods.ConsummerSubscriber<String>()

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
    def subscriber = new ReactiveStreamsTracedMethods.ConsummerSubscriber<String>()

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
          }
        }
      }
    }
  }
}

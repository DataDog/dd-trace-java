import annotatedsample.ReactiveStreamsTracedMethods
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags

import static annotatedsample.ReactiveStreamsTracedMethods.DELAY

class ReactiveStreamsAsyncResultSupportExtensionTest extends AgentTestRunner {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
    injectSysConfig("dd.integration.reactive-streams-1.enabled", "true")
  }

  def "test WithSpan annotated async method (Publisher)"() {
    setup:
    def publisher = ReactiveStreamsTracedMethods.traceAsyncPublisher()
    def subscriber = new ReactiveStreamsTracedMethods.StubSubscriber<String>()

    when:
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncPublisher"
          duration { it > DELAY.toNanos() }
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
    def expectedException = new IllegalStateException("Test exception")
    def publisher = ReactiveStreamsTracedMethods.traceAsyncFailingPublisher(expectedException)
    def subscriber = new ReactiveStreamsTracedMethods.StubSubscriber<String>()


    when:
    publisher.subscribe(subscriber)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactiveStreamsTracedMethods.traceAsyncFailingPublisher"
          operationName "ReactiveStreamsTracedMethods.traceAsyncFailingPublisher"
          duration { it > DELAY.toNanos() }
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
}

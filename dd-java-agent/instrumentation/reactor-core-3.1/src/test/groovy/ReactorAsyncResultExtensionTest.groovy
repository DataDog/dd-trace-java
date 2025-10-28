import annotatedsample.ReactorTracedMethods
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ReactorAsyncResultExtensionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.otel.enabled", "true")
    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
  }

  @Shared
  ListeningExecutorService executor

  def setupSpec() {
    this.executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
  }

  def cleanupSpec() {
    this.executor.shutdownNow()
  }

  def "test WithSpan annotated async method (Mono)"() {
    setup:
    def latch = new CountDownLatch(1)
    def mono = ReactorTracedMethods.traceAsyncMono(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    mono.block()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncMono"
          operationName "ReactorTracedMethods.traceAsyncMono"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing Mono)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new IllegalStateException("Test exception")
    def mono = ReactorTracedMethods.traceAsyncFailingMono(latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    mono.block()

    then:
    thrown(IllegalStateException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncFailingMono"
          operationName "ReactorTracedMethods.traceAsyncFailingMono"
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

  def "test WithSpan annotated async method (cancelled Mono)"() {
    setup:
    def latch = new CountDownLatch(1)
    def mono = ReactorTracedMethods.traceAsyncMono(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    mono.subscribe(new ReactorTracedMethods.CancelSubscriber<>())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncMono"
          operationName "ReactorTracedMethods.traceAsyncMono"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (Flux)"() {
    setup:
    def latch = new CountDownLatch(1)
    def flux = ReactorTracedMethods.traceAsyncFlux(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    flux.blockLast()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncFlux"
          operationName "ReactorTracedMethods.traceAsyncFlux"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing Flux)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new IllegalStateException("Test exception")
    def flux = ReactorTracedMethods.traceAsyncFailingFlux(latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    flux.blockLast()

    then:
    thrown(IllegalStateException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncFailingFlux"
          operationName "ReactorTracedMethods.traceAsyncFailingFlux"
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

  def "test WithSpan annotated async method (cancelled Flux)"() {
    setup:
    def latch = new CountDownLatch(1)
    def flux = ReactorTracedMethods.traceAsyncFlux(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    flux.subscribe(new ReactorTracedMethods.CancelSubscriber<>())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "ReactorTracedMethods.traceAsyncFlux"
          operationName "ReactorTracedMethods.traceAsyncFlux"
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

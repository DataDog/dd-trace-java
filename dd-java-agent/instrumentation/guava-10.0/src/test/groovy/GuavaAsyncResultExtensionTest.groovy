import annotatedsample.GuavaTracedMethods
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GuavaAsyncResultExtensionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry-annotations-1.20.enabled", "true")
  }

  @Shared
  ExecutorService executor

  def setupSpec() {
    this.executor = Executors.newSingleThreadExecutor()
  }

  def cleanupSpec() {
    this.executor.shutdownNow()
  }

  def "test WithSpan annotated async method (ListenableFuture)"() {
    setup:
    def latch = new CountDownLatch(1)
    def listenableFuture = GuavaTracedMethods.traceAsyncListenableFuture(executor, latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    listenableFuture.get()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "GuavaTracedMethods.traceAsyncListenableFuture"
          operationName "GuavaTracedMethods.traceAsyncListenableFuture"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (cancelled ListenableFuture)"() {
    setup:
    def latch = new CountDownLatch(1)
    def listenableFuture = GuavaTracedMethods.traceAsyncCancelledListenableFuture(latch)

    expect:
    TEST_WRITER.size() == 0

    when:
    listenableFuture.cancel(true)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "GuavaTracedMethods.traceAsyncCancelledListenableFuture"
          operationName "GuavaTracedMethods.traceAsyncCancelledListenableFuture"
          tags {
            defaultTags()
            "$Tags.COMPONENT" "opentelemetry"
            "$Tags.SPAN_KIND" "internal"
          }
        }
      }
    }
  }

  def "test WithSpan annotated async method (failing ListenableFuture)"() {
    setup:
    def latch = new CountDownLatch(1)
    def expectedException = new IllegalStateException("Test exception")
    def listenableFuture = GuavaTracedMethods.traceAsyncFailingListenableFuture(executor, latch, expectedException)

    expect:
    TEST_WRITER.size() == 0

    when:
    latch.countDown()
    listenableFuture.get()

    then:
    thrown(ExecutionException)
    assertTraces(1) {
      trace(1) {
        span {
          resourceName "GuavaTracedMethods.traceAsyncFailingListenableFuture"
          operationName "GuavaTracedMethods.traceAsyncFailingListenableFuture"
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
}

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.AsyncResponse
import jakarta.ws.rs.container.Suspended

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for GH-12597 (jax-rs.request span double-finish / premature finish on
 * synchronous AsyncResponse#resume()/cancel()) directly against the jakarta.ws.rs advice, with no
 * real JAX-RS server involved -- resource methods are invoked directly, exactly like the existing
 * JakartaRsAnnotations3InstrumentationTest does. This exercises the same advice code as the
 * cxf-2.1 module's CxfContextPropagationTest (which only covers the javax.ws.rs path).
 */
class JakartaRsAsyncResponseInstrumentationTest extends InstrumentationSpecification {

  @Override
  protected boolean enabledFinishTimingChecks() {
    // Fails the test with the exact "finished more than once" stack traces if a jax-rs.request
    // span is ever finished twice -- see CxfContextPropagationTest for the same guard.
    return true
  }

  def "resume() called synchronously from within the resource method finishes the span only once"() {
    setup:
    def response = new FakeAsyncResponse()

    when:
    new AsyncResumeResource().resumeThenWork(response)

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          operationName "jakarta-rs.request"
          resourceName "GET /asyncresume"
          spanType "web"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "$Tags.HTTP_ROUTE" "/asyncresume"
            defaultTags()
          }
        }
        // Still parented under jakarta-rs.request: proves the span wasn't finished (and its
        // scope wasn't popped) by resume() itself, before the resource method returned.
        TraceUtils.basicSpan(it, "trace.annotation", "AsyncResumeResource.doWorkAfterResume", span(0), null, ["component": "trace"])
      }
    }
  }

  def "cancel() called synchronously from within the resource method finishes the span only once"() {
    setup:
    def response = new FakeAsyncResponse()

    when:
    new AsyncCancelResource().cancelThenWork(response)

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          operationName "jakarta-rs.request"
          resourceName "GET /asynccancel"
          spanType "web"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "$Tags.HTTP_ROUTE" "/asynccancel"
            "canceled" true
            defaultTags()
          }
        }
        TraceUtils.basicSpan(it, "trace.annotation", "AsyncCancelResource.doWorkAfterCancel", span(0), null, ["component": "trace"])
      }
    }
  }

  def "resume() called synchronously from a nested @Trace helper finishes the span only once"() {
    // The gap found in code review: resume() called from a @Trace-annotated helper, not
    // directly from the resource method's own body. activeSpan() at that moment is the
    // helper's span, not the resource method's.
    setup:
    def response = new FakeAsyncResponse()

    when:
    new NestedResumeResource().resumeViaHelper(response)

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          operationName "jakarta-rs.request"
          resourceName "GET /nestedresume"
          spanType "web"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "$Tags.HTTP_ROUTE" "/nestedresume"
            defaultTags()
          }
        }
        TraceUtils.basicSpan(it, "trace.annotation", "NestedResumeResource.resumeFromHelper", span(0), null, ["component": "trace"])
      }
    }
  }

  def "resume() called from a genuinely different thread is unaffected"() {
    setup:
    def response = new FakeAsyncResponse()

    when:
    new TrueAsyncResumeResource().suspendThenResumeFromAnotherThread(response)

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          operationName "jakarta-rs.request"
          resourceName "GET /trueasyncresume"
          spanType "web"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "$Tags.HTTP_ROUTE" "/trueasyncresume"
            defaultTags()
          }
        }
        TraceUtils.basicSpan(it, "trace.annotation", "TrueAsyncResumeResource.doWorkOnBackgroundThread", span(0), null, ["component": "trace"])
      }
    }
  }

  @Path("/asyncresume")
  static class AsyncResumeResource {
    @GET
    void resumeThenWork(@Suspended final AsyncResponse response) {
      response.resume("OK")
      doWorkAfterResume()
    }

    @Trace
    private void doWorkAfterResume() {}
  }

  @Path("/asynccancel")
  static class AsyncCancelResource {
    @GET
    void cancelThenWork(@Suspended final AsyncResponse response) {
      response.cancel()
      doWorkAfterCancel()
    }

    @Trace
    private void doWorkAfterCancel() {}
  }

  @Path("/nestedresume")
  static class NestedResumeResource {
    @GET
    void resumeViaHelper(@Suspended final AsyncResponse response) {
      resumeFromHelper(response)
    }

    @Trace
    private void resumeFromHelper(final AsyncResponse response) {
      response.resume("OK")
    }
  }

  @Path("/trueasyncresume")
  static class TrueAsyncResumeResource {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2)

    @GET
    void suspendThenResumeFromAnotherThread(@Suspended final AsyncResponse response) {
      EXECUTOR.submit({
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!response.isSuspended() && System.nanoTime() < deadline) {
          Thread.sleep(1)
        }
        doWorkOnBackgroundThread()
        response.resume("OK")
      })
    }

    @Trace
    private void doWorkOnBackgroundThread() {}
  }
}

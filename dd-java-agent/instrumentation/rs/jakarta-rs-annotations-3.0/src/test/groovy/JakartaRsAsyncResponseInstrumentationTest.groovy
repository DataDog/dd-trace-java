import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.AsyncResponse
import jakarta.ws.rs.container.Suspended

import java.util.concurrent.CountDownLatch
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

  def "resume() called synchronously from a nested resource method finishes the outer span only once"() {
    // Regression test for a gap found reviewing the fix: resume() is called on the OUTER
    // resource method's response, but from inside a DIFFERENT resource method invoked
    // synchronously from within the outer one (not a bare @Trace helper). That inner resource
    // method pushes its own entry onto ResourceMethodSpanTracker's stack, on top of the
    // outer's, so the outer's span is no longer the innermost entry -- checking only the top
    // of the stack (an earlier version of this fix) would treat the outer's resume() as
    // genuinely async and finish its span right there, then finish it again when the outer
    // resource method itself returns (caught by enabledFinishTimingChecks()).
    setup:
    def outerResponse = new FakeAsyncResponse()

    when:
    new OuterResumeResource().outerThenCallInner(outerResponse)

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          // The route/resource name/component tag get overwritten to reflect the
          // (synchronously) nested resource method -- pre-existing decorator behavior for a
          // resource method invoked from within another one (e.g. a sub-resource locator),
          // unrelated to this fix. What this test actually checks is that this span is still
          // open (not finished, its scope not popped) when the child span below starts, and
          // finished only once overall.
          operationName "jakarta-rs.request"
          resourceName "GET /innerresume"
          spanType "web"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs"
            "$Tags.HTTP_ROUTE" "/innerresume"
            // component was overwritten to "jakarta-rs" above, but this span's own
            // integration is still "jakarta-rs-controller" (set when the span was created);
            // asserting that explicitly skips defaultTags()'s usual component == integration
            // check, which doesn't hold once component has been overwritten like this.
            withCustomIntegrationName("jakarta-rs-controller")
            defaultTags()
          }
        }
        span {
          operationName "jakarta-rs.request"
          resourceName "InnerResumeResource.resolveOuterAndOwnResponse"
          spanType "web"
          errored false
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            defaultTags()
          }
        }
        // Still parented under the outer's jakarta-rs.request: proves the outer span wasn't
        // finished (and its scope wasn't popped) by the inner resource method's call to
        // outerResponse.resume(), before the outer resource method returned.
        TraceUtils.basicSpan(it, "trace.annotation", "OuterResumeResource.doWorkAfterInner", span(0), null, ["component": "trace"])
      }
    }
  }

  def "resume() called from a genuinely different thread is unaffected"() {
    setup:
    def response = new FakeAsyncResponse()
    def resource = new TrueAsyncResumeResource()

    when:
    resource.suspendThenResumeFromAnotherThread(response)
    // Only release the background resume() once this call -- and therefore the resource
    // method's own exit advice, which runs synchronously inside it -- has returned. Without
    // this, the background thread could call resume() before the exit advice runs, which
    // would still finish the span exactly once (ContextStore#remove is the atomic claim), but
    // it would race the exit advice nondeterministically instead of testing the case this test
    // is meant to cover: resume() from a thread that starts after the resource method exits.
    resource.methodReturned.countDown()

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

  @Path("/outerresume")
  static class OuterResumeResource {
    @GET
    void outerThenCallInner(@Suspended final AsyncResponse response) {
      new InnerResumeResource().resolveOuterAndOwnResponse(new FakeAsyncResponse(), response)
      doWorkAfterInner()
    }

    @Trace
    private void doWorkAfterInner() {}
  }

  @Path("/innerresume")
  static class InnerResumeResource {
    @GET
    void resolveOuterAndOwnResponse(
      @Suspended final AsyncResponse ownResponse, final AsyncResponse outerResponse) {
      // Resolve the outer's response from inside this (different) resource method's own
      // invocation, then resolve this method's own response too so its span finishes
      // normally when this method returns, instead of being left open forever.
      outerResponse.resume("OK")
      ownResponse.resume("OK")
    }
  }

  @Path("/trueasyncresume")
  static class TrueAsyncResumeResource {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2)

    final CountDownLatch methodReturned = new CountDownLatch(1)

    @GET
    void suspendThenResumeFromAnotherThread(@Suspended final AsyncResponse response) {
      EXECUTOR.submit({
        methodReturned.await(5, TimeUnit.SECONDS)
        doWorkOnBackgroundThread()
        response.resume("OK")
      })
    }

    @Trace
    private void doWorkOnBackgroundThread() {}
  }
}

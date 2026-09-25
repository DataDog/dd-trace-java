import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.Request
import org.apache.cxf.endpoint.Server
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean
import org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider
import org.apache.cxf.transport.http_jetty.JettyHTTPDestination
import org.apache.cxf.transport.http_jetty.JettyHTTPServerEngine
import spock.lang.Shared

class CxfContextPropagationTest extends InstrumentationSpecification {

  @Shared
  Server server

  @Shared
  int port

  @Override
  void setupSpec() {
    JAXRSServerFactoryBean sf = new JAXRSServerFactoryBean()
    sf.setResourceClasses(TestResource, AsyncResumeResource, TrueAsyncResumeResource, AsyncCancelResource, NestedResumeResource)
    List<Object> providers = [new TestExceptionMapper()]
    sf.setProviders(providers)

    sf.setResourceProvider(TestResource,
      new SingletonResourceProvider(new TestResource(), true))
    sf.setResourceProvider(AsyncResumeResource,
      new SingletonResourceProvider(new AsyncResumeResource(), true))
    sf.setResourceProvider(TrueAsyncResumeResource,
      new SingletonResourceProvider(new TrueAsyncResumeResource(), true))
    sf.setResourceProvider(AsyncCancelResource,
      new SingletonResourceProvider(new AsyncCancelResource(), true))
    sf.setResourceProvider(NestedResumeResource,
      new SingletonResourceProvider(new NestedResumeResource(), true))
    sf.setAddress("http://localhost:0")

    server = sf.create()
    server.start()
    port = ((JettyHTTPServerEngine)((JettyHTTPDestination)server.getDestination()).getEngine()).getConnector().getLocalPort()
  }

  @Override
  void cleanupSpec() {
    server?.stop()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    // Regression guard for https://github.com/DataDog/dd-trace-java/issues/12597:
    // fails the test with the exact "finished more than once" stack traces if the
    // jax-rs.request span is ever finished twice (e.g. once from AsyncResponse#resume()
    // and again from the resource method's own exit advice).
    return true
  }

  def "should propagate context on async request resume"() {
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/test")
      .get().build()).execute()
    then:
    assert response.code() == 200
    assert response.body().string() == "Failure"

    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/test"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_ROUTE" String
            "servlet.path" { it == null || it == "/test" }
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.NETWORK_CLIENT_IP" "127.0.0.1"
            withCustomIntegrationName("jetty-server")
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "TestResource.someService"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "error.message" { String }
            "error.type" { String }
            "error.stack" { String }
            defaultTags()
          }
        }
        TraceUtils.basicSpan(it, "trace.annotation", "TestResource.doSomething",span(1), null, ["component": "trace"] )
        TraceUtils.basicSpan(it, "trace.annotation", "TestExceptionMapper.toResponse",span(0), null, ["component": "trace"] )
      }
    }
  }

  def "resume() called synchronously from within the resource method finishes the span only once"() {
    // Regression test for https://github.com/DataDog/dd-trace-java/issues/12597: when
    // AsyncResponse#resume() is called synchronously (not truly suspended, the resource
    // method keeps running), the resource method's own jax-rs.request scope is still on
    // top of the scope stack. Before the fix, JakartaRsAsyncResponseInstrumentation /
    // JaxRsAsyncResponseInstrumentation would eagerly finish the span there, so any work
    // done afterwards (here: doWorkAfterResume()) would be attributed as a child of an
    // already-finished span, and the resource method's own exit advice would finish the
    // same span a second time (caught by enabledFinishTimingChecks()).
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/asyncresume")
      .get().build()).execute()
    then:
    assert response.code() == 200
    assert response.body().string() == "OK"

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /asyncresume"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/asyncresume"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_ROUTE" String
            "servlet.path" { it == null || it == "/asyncresume" }
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.NETWORK_CLIENT_IP" "127.0.0.1"
            withCustomIntegrationName("jetty-server")
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "AsyncResumeResource.resumeThenWork"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        // Still parented under jax-rs.request: proves that span wasn't finished (and its
        // scope wasn't popped) by resume() itself, before the resource method returned.
        TraceUtils.basicSpan(it, "trace.annotation", "AsyncResumeResource.doWorkAfterResume", span(1), null, ["component": "trace"])
      }
    }
  }

  def "cancel() called synchronously from within the resource method finishes the span only once"() {
    // Same regression as above, but for AsyncResponseCancelAdvice: cancel() is called
    // synchronously and the resource method keeps running afterwards.
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/asynccancel")
      .get().build()).execute()
    then:
    assert response.code() == 503

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /asynccancel"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/asynccancel"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 503
            "$Tags.HTTP_ROUTE" String
            "servlet.path" { it == null || it == "/asynccancel" }
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.NETWORK_CLIENT_IP" "127.0.0.1"
            withCustomIntegrationName("jetty-server")
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "AsyncCancelResource.cancelThenWork"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            "canceled" true
            defaultTags()
          }
        }
        // Still parented under jax-rs.request: proves the span wasn't finished (and its
        // scope wasn't popped) by cancel() itself, before the resource method returned.
        TraceUtils.basicSpan(it, "trace.annotation", "AsyncCancelResource.doWorkAfterCancel", span(1), null, ["component": "trace"])
      }
    }
  }

  def "resume() called from a genuinely different thread (textbook async pattern) is unaffected"() {
    // Regression guard the other way: the fix must not change the standard cross-thread
    // async pattern, where the resource method returns without resolving anything and a
    // completely different thread calls resume() later. Here the span IS finished by the
    // resume() advice (activeSpan() on that other thread is not this span), exactly as
    // before the fix.
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/trueasyncresume")
      .get().build()).execute()
    then:
    assert response.code() == 200
    assert response.body().string() == "OK"

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /trueasyncresume"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/trueasyncresume"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_ROUTE" String
            "servlet.path" { it == null || it == "/trueasyncresume" }
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.NETWORK_CLIENT_IP" "127.0.0.1"
            withCustomIntegrationName("jetty-server")
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "TrueAsyncResumeResource.suspendThenResumeFromAnotherThread"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        // Runs on the background thread, before resume() -- still correctly parented
        // under the (still-open, cross-thread-propagated) jax-rs.request span.
        TraceUtils.basicSpan(it, "trace.annotation", "TrueAsyncResumeResource.doWorkOnBackgroundThread", span(1), null, ["component": "trace"])
      }
    }
  }

  def "resume() called synchronously from a nested @Trace helper finishes the span only once"() {
    // Regression test for a gap found reviewing the fix for GH-12597: resume() is called
    // synchronously, but from a @Trace-annotated helper method rather than directly from
    // the resource method's own body. At that moment, the *helper's* span is the active
    // one on this thread, not the resource method's -- checking activeSpan() against the
    // resource method's span directly (an earlier version of this fix) would wrongly treat
    // this as a genuinely-async resume and finish the span right there, then finish it
    // again when the resource method itself returns (caught by enabledFinishTimingChecks()).
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/nestedresume")
      .get().build()).execute()
    then:
    assert response.code() == 200
    assert response.body().string() == "OK"

    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /nestedresume"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/nestedresume"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_ROUTE" String
            "servlet.path" { it == null || it == "/nestedresume" }
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.NETWORK_CLIENT_IP" "127.0.0.1"
            withCustomIntegrationName("jetty-server")
            defaultTags()
          }
        }
        span {
          operationName "jax-rs.request"
          resourceName "NestedResumeResource.resumeViaHelper"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        // The helper that actually calls resume() -- still parented under jax-rs.request,
        // proving the resource-method span wasn't finished/popped while the helper (and
        // resume() inside it) was still running.
        TraceUtils.basicSpan(it, "trace.annotation", "NestedResumeResource.resumeFromHelper", span(1), null, ["component": "trace"])
      }
    }
  }
}

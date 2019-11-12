package datadog.trace.agent.test.base

import datadog.opentracing.DDSpan
import datadog.trace.agent.decorator.HttpClientDecorator
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.instrumentation.api.Tags
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.ExecutionException

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.ConfigUtils.withConfigOverride
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpClientTest<DECORATOR extends HttpClientDecorator> extends AgentTestRunner {
  protected static final BODY_METHODS = ["POST", "PUT"]

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("success") {
        handleDistributedRequest()
        String msg = "Hello."
        response.status(200).send(msg)
      }
      prefix("error") {
        handleDistributedRequest()
        String msg = "Sorry."
        response.status(500).send(msg)
      }
      prefix("redirect") {
        handleDistributedRequest()
        redirect(server.address.resolve("/success").toURL().toString())
      }
      prefix("another-redirect") {
        handleDistributedRequest()
        redirect(server.address.resolve("/redirect").toURL().toString())
      }
      prefix("circular-redirect") {
        handleDistributedRequest()
        redirect(server.address.resolve("/circular-redirect").toURL().toString())
      }
    }
  }

  @Shared
  DECORATOR clientDecorator = decorator()

  /**
   * Make the request and return the status code response
   * @param method
   * @return
   */
  abstract int doRequest(String method, URI uri, Map<String, String> headers = [:], Closure callback = null)

  abstract DECORATOR decorator()

  Integer statusOnRedirectError() {
    return null
  }

  def "basic #method request #url - tagQueryString=#tagQueryString"() {
    when:
    def status = withConfigOverride(Config.HTTP_CLIENT_TAG_QUERY_STRING, "$tagQueryString") {
      doRequest(method, url)
    }

    then:
    status == 200
    assertTraces(2) {
      server.distributedRequestTrace(it, 0, trace(1).last())
      trace(1, size(1)) {
        clientSpan(it, 0, null, method, false, tagQueryString, url)
      }
    }

    where:
    path                                | tagQueryString
    "/success"                          | false
    "/success"                          | true
    "/success?with=params"              | false
    "/success?with=params"              | true
    "/success#with+fragment"            | true
    "/success?with=params#and=fragment" | true

    method = "GET"
    url = server.address.resolve(path)
  }

  def "basic #method request with parent"() {
    when:
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"))
    }

    then:
    status == 200
    assertTraces(2) {
      server.distributedRequestTrace(it, 0, trace(1).last())
      trace(1, size(2)) {
        basicSpan(it, 0, "parent")
        clientSpan(it, 1, span(0), method, false)
      }
    }

    where:
    method << BODY_METHODS
  }

  //FIXME: add tests for POST with large/chunked data

  def "basic #method request with split-by-domain"() {
    when:
    def status = withConfigOverride(Config.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true") {
      doRequest(method, server.address.resolve("/success"))
    }

    then:
    status == 200
    assertTraces(2) {
      server.distributedRequestTrace(it, 0, trace(1).last())
      trace(1, size(1)) {
        clientSpan(it, 0, null, method, true)
      }
    }

    where:
    method = "HEAD"
  }

  def "trace request without propagation"() {
    when:
    def status = withConfigOverride(Config.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService") {
      runUnderTrace("parent") {
        doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"])
      }
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(1) {
      trace(0, size(2)) {
        basicSpan(it, 0, "parent")
        clientSpan(it, 1, span(0), method, renameService)
      }
    }

    where:
    method = "GET"
    renameService << [false, true]
  }

  def "trace request with callback and parent"() {
    when:
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"]) {
        runUnderTrace("child") {}
      }
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(1) {
      trace(0, size(3)) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "child", span(0))
        clientSpan(it, 2, span(0), method, false)
      }
    }

    where:
    method = "GET"
  }

  def "trace request with callback and no parent"() {
    when:
    def status = doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"]) {
      runUnderTrace("callback") {
        // Ensure consistent ordering of traces for assertion.
        TEST_WRITER.waitForTraces(1)
      }
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(2) {
      trace(0, size(1)) {
        clientSpan(it, 0, null, method, false)
      }
      trace(1, 1) {
        basicSpan(it, 0, "callback")
      }
    }

    where:
    method = "GET"
  }

  def "basic #method request with 1 redirect"() {
    // TODO quite a few clients create an extra span for the redirect
    // This test should handle both types or we should unify how the clients work

    given:
    assumeTrue(testRedirects())
    def uri = server.address.resolve("/redirect")

    when:
    def status = doRequest(method, uri)

    then:
    status == 200
    assertTraces(3) {
      server.distributedRequestTrace(it, 0, trace(2).last())
      server.distributedRequestTrace(it, 1, trace(2).last())
      trace(2, size(1)) {
        clientSpan(it, 0, null, method, false, false, uri)
      }
    }

    where:
    method = "GET"
  }

  def "basic #method request with 2 redirects"() {
    given:
    assumeTrue(testRedirects())
    def uri = server.address.resolve("/another-redirect")

    when:
    def status = doRequest(method, uri)

    then:
    status == 200
    assertTraces(4) {
      server.distributedRequestTrace(it, 0, trace(3).last())
      server.distributedRequestTrace(it, 1, trace(3).last())
      server.distributedRequestTrace(it, 2, trace(3).last())
      trace(3, size(1)) {
        clientSpan(it, 0, null, method, false, false, uri)
      }
    }

    where:
    method = "GET"
  }

  def "basic #method request with circular redirects"() {
    given:
    assumeTrue(testRedirects())
    assumeTrue(testCircularRedirects())
    def uri = server.address.resolve("/circular-redirect")

    when:
    doRequest(method, uri)//, ["is-dd-server": "false"])

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(3) {
      server.distributedRequestTrace(it, 0, trace(2).last())
      server.distributedRequestTrace(it, 1, trace(2).last())
      trace(2, size(1)) {
        clientSpan(it, 0, null, method, false, false, uri, statusOnRedirectError(), thrownException)
      }
    }

    where:
    method = "GET"
  }

  def "connection error (unopened port)"() {
    given:
    assumeTrue(testConnectionFailure())
    def uri = new URI("http://localhost:$UNUSABLE_PORT/")

    when:
    runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent", null, thrownException)
        clientSpan(it, 1, span(0), method, false, false, uri, null, thrownException)
      }
    }

    where:
    method = "GET"
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", boolean renameService = false, boolean tagQueryString = false, URI uri = server.address.resolve("/success"), Integer status = 200, Throwable exception = null) {
    trace.span(index) {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      serviceName renameService ? "localhost" : "unnamed-java-app"
      operationName expectedOperationName()
      resourceName "$method $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored exception != null
      tags {
        defaultTags()
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        "$Tags.COMPONENT" clientDecorator.component()
        if (status) {
          "$Tags.HTTP_STATUS" status
        }
        "$Tags.HTTP_URL" "${uri.resolve(uri.path)}"
        if (tagQueryString) {
          "$DDTags.HTTP_QUERY" uri.query
          "$DDTags.HTTP_FRAGMENT" { it == null || it == uri.fragment } // Optional
        }
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" uri.port
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
        "$Tags.HTTP_METHOD" method
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
      }
    }
  }

  String expectedOperationName() {
    return "http.request"
  }

  int size(int size) {
    size
  }

  boolean testRedirects() {
    true
  }

  boolean testCircularRedirects() {
    true
  }

  boolean testConnectionFailure() {
    true
  }
}

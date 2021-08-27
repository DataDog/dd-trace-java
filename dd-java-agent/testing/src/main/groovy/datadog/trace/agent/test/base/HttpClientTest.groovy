package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.server.http.HttpProxy
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import spock.lang.AutoCleanup
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpClientTest extends AgentTestRunner {
  protected static final BODY_METHODS = ["POST", "PUT"]
  protected static final int CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(3) as int
  protected static final int READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5) as int
  protected static final BASIC_AUTH_KEY = "custom_authorization_header"
  protected static final BASIC_AUTH_VAL = "plain text auth token"

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
      prefix("secured") {
        handleDistributedRequest()
        if (request.headers.get(BASIC_AUTH_KEY) == BASIC_AUTH_VAL) {
          response.status(200).send("secured string under basic auth")
        } else {
          response.status(401).send("Unauthorized")
        }
      }
      prefix("to-secured") {
        handleDistributedRequest()
        redirect(server.address.resolve("/secured").toURL().toString())
      }
    }
  }

  @AutoCleanup
  @Shared
  def proxy = new HttpProxy()

  @Shared
  ProxySelector proxySelector

  @Shared
  String component = component()

  def setupSpec() {
    List<Proxy> proxyList = Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.port)))
    proxySelector = new ProxySelector() {
        @Override
        List<Proxy> select(URI uri) {
          if (uri.fragment == "proxy") {
            return proxyList
          }
          return getDefault().select(uri)
        }

        @Override
        void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
          getDefault().connectFailed(uri, sa, ioe)
        }
      }
  }

  /**
   * Make the request and return the status code response
   * @param method
   * @return
   */
  abstract int doRequest(String method, URI uri, Map<String, String> headers = [:], String body = "", Closure callback = null)

  String keyStorePath() {
    server.keystorePath
  }

  static String keyStorePassword() {
    "datadog"
  }

  abstract CharSequence component()

  Integer statusOnRedirectError() {
    return null
  }

  def "basic #method request #url - tagQueryString=#tagQueryString"() {
    when:
    injectSysConfig(HTTP_CLIENT_TAG_QUERY_STRING, "$tagQueryString")
    def status = doRequest(method, url)

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, tagQueryString, url)
      }
      server.distributedRequestTrace(it, trace(0).last())
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

  // IBM JVM has different protocol support for TLS
  @Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
  def "basic secure #method request"() {
    given:
    assumeTrue(testSecure())

    when:
    def status = doRequest(method, url)

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method | _
    "GET"  | _
    "POST" | _

    path = "/success"
    url = server.secureAddress.resolve(path)
  }

  // IBM JVM has different protocol support for TLS
  @Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
  def "secure #method proxied request"() {
    given:
    assumeTrue(testSecure() && testProxy())

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, url, [:], body)
    }
    println("RESPONSE: $status")

    then:
    status == 200
    TEST_WRITER
    assertTraces(2) {
      def remoteParentSpan = null
      trace(size(3)) {
        sortSpansByStart()
        remoteParentSpan = span(2)
        basicSpan(it, "parent")
        clientSpan(it, span(0), "CONNECT", false, false, new URI("http://localhost:$server.secureAddress.port/"))
        clientSpan(it, span(0), method, false, false, url)
      }
      server.distributedRequestTrace(it, remoteParentSpan)
    }

    where:
    method << BODY_METHODS
    url = server.secureAddress.resolve("/success#proxy") // fragment indicates the request should be proxied.
    body = (1..10000).join(" ")
  }

  def "basic #method request with parent"() {
    when:
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), [:], body)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(2)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), method)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method << BODY_METHODS
    body = (1..10000).join(" ")
  }

  def "server error request with parent"() {
    setup:
    def uri = server.address.resolve("/error")

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    status == 500
    assertTraces(2) {
      trace(size(2)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), method, false, false, uri, 500, false) // not an error.
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method | _
    "GET"  | _
    "POST" | _
  }

  def "client error request with parent"() {
    setup:
    def uri = server.address.resolve("/secured")

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    status == 401
    assertTraces(2) {
      trace(size(2)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), method, false, false, uri, 401, true)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method | _
    "GET"  | _
    "POST" | _
  }

  //FIXME: add tests for POST with large/chunked data

  def "basic #method request with split-by-domain"() {
    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "true")
    def status = doRequest(method, server.address.resolve("/success"))

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, true)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method = "HEAD"
  }

  def "trace request without propagation"() {
    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"])
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(1) {
      trace(size(2)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), method, renameService)
      }
    }

    where:
    method = "GET"
    renameService << [false, true]
  }

  def "trace request with callback and parent"() {
    given:
    assumeTrue(testCallbackWithParent())

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"], "") {
        runUnderTrace("child") {
          blockUntilChildSpansFinished(1)
        }
      }
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(1) {
      sortSpansByStart()
      trace(size(3)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), method)
        basicSpan(it, "child", span(0))
      }
    }

    where:
    method = "GET"
  }

  def "trace request with callback and no parent"() {
    when:
    def status = doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"], "") {
      runUnderTrace("callback") {
        // FIXME: since in async we may not have the other trace report until the callback is done
        //  we should add a test method to detect that the other trace is finished but waiting for
        //  references to clear out in order to validate the behavior that the client spans are
        //  finished regardless of the callback operation
        // PendingTrace.pendingTraces(1) or TEST_WRITER.waitForPendingTraces(1)
      }
    }

    TEST_WRITER.waitForTraces(2)

    // Java 7 CopyOnWrite lists cannot be sorted in place
    List<List<DDSpan>> traces = TEST_WRITER.toList()
    traces.sort({ t1, t2 ->
      return t1[0].startTimeNano <=> t2[0].startTimeNano
    })
    for (int i = 0; i < traces.size(); i++) {
      TEST_WRITER.set(i, traces.get(i))
    }

    then:
    status == 200
    // only one trace (client).
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method)
      }
      trace(1) {
        basicSpan(it, "callback")
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
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
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
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method = "GET"
  }

  def "basic #method request with circular redirects"() {
    given:
    assumeTrue(testRedirects() && testCircularRedirects())
    def uri = server.address.resolve("/circular-redirect")

    when:
    doRequest(method, uri)//, ["is-dd-server": "false"])

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(3) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri, statusOnRedirectError(), true, thrownException)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method = "GET"
  }

  def "redirect #method to secured endpoint copies auth header"() {
    given:
    assumeTrue(testRedirects())
    def uri = server.address.resolve("/to-secured")

    when:
    def status = doRequest(method, uri, [(BASIC_AUTH_KEY): BASIC_AUTH_VAL])

    then:
    status == 200
    assertTraces(3) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
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
      trace(2) {
        basicSpan(it, "parent", null, thrownException)
        clientSpan(it, span(0), method, false, false, uri, null, true, thrownException)
      }
    }

    where:
    method = "GET"
  }

  def "connection error non routable address"() {
    given:
    assumeTrue(testRemoteConnection())
    def uri = new URI("https://192.0.2.1/")

    when:
    runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex
    assertTraces(1) {
      trace(size(2)) {
        basicSpan(it, "parent", null, thrownException)
        clientSpan(it, span(0), method, false, false, uri, null, true, thrownException)
      }
    }

    where:
    method = "HEAD"
  }

  // IBM JVM has different protocol support for TLS
  @Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
  def "test https request"() {
    given:
    assumeTrue(testRemoteConnection())
    def uri = new URI("https://www.google.com/")

    when:
    def status = doRequest(method, uri)

    then:
    status == 200
    assertTraces(1) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
    }

    where:
    method = "HEAD"
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void clientSpan(TraceAssert trace, Object parentSpan, String method = "GET", boolean renameService = false, boolean tagQueryString = false, URI uri = server.address.resolve("/success"), Integer status = 200, boolean error = false, Throwable exception = null, boolean ignorePeer = false) {
    trace.span {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      if (renameService) {
        serviceName uri.host
      }
      operationName expectedOperationName()
      resourceName "$method $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored error
      measured true
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" { it == uri.host || ignorePeer }
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" || ignorePeer } // Optional
        "$Tags.PEER_PORT" { it == null || it == uri.port || it == proxy.port || it == 443 || ignorePeer }
        "$Tags.HTTP_URL" "${uri.resolve(uri.path)}" // remove fragment
        "$Tags.HTTP_METHOD" method
        if (status) {
          "$Tags.HTTP_STATUS" status
        }
        if (tagQueryString) {
          "$DDTags.HTTP_QUERY" uri.query
          "$DDTags.HTTP_FRAGMENT" { it == null || it == uri.fragment } // Optional
        }
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        defaultTags()
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

  /**
   * Uses a local self-signed cert, so the client must be configured to ignore cert errors.
   */
  boolean testSecure() {
    false
  }

  /**
   * Client must be configured to use proxy iff url fragment is "proxy".
   */
  boolean testProxy() {
    false
  }

  boolean testRemoteConnection() {
    true
  }

  boolean testCallbackWithParent() {
    // FIXME: this hack is here because callback with parent is broken in play-ws when the stream()
    // function is used.  There is no way to stop a test from a derived class hence the flag
    true
  }
}

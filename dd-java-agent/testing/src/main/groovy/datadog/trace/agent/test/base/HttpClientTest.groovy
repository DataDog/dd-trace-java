package datadog.trace.agent.test.base

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.server.http.HttpProxy
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.TracerConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.test.util.Flaky
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
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS
import static org.junit.Assume.assumeTrue

@Unroll
abstract class HttpClientTest extends VersionedNamingTestBase {
  protected static final BODY_METHODS = ["POST", "PUT"]
  protected static final int CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(3) as int
  protected static final int READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5) as int
  protected static final BASIC_AUTH_KEY = "custom_authorization_header"
  protected static final BASIC_AUTH_VAL = "plain text auth token"
  protected static final DSM_EDGE_TAGS = CLIENT_PATHWAY_EDGE_TAGS.collect { key, value ->
    return key + ":" + value
  }

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
      prefix("respond-with-header") {
        handleDistributedRequest()
        String msg = "Hello."
        response.status(200)
          .addHeader('x-datadog-test-response-header', 'baz')
          .send(msg)
      }
      prefix("/timeout") {
        Thread.sleep(10_000)
        throw new IllegalStateException("Should never happen")
      }
    }
  }

  @AutoCleanup
  @Shared
  def proxy = new HttpProxy()

  @Shared
  ProxySelector proxySelector

  String component = component()

  @Override
  boolean isDataStreamsEnabled() {
    true
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // we inject this config because it's statically assigned and we cannot inject this at test level without forking
    // not starting with "/" made full url (http://..) matching but not the path portion (because starting with /)
    // this settings should not affect test results
    injectSysConfig(TracerConfig.TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING, "**/success:*")

    injectSysConfig(HEADER_TAGS, 'x-datadog-test-both-header:both_header_tag')
    injectSysConfig(REQUEST_HEADER_TAGS, 'x-datadog-test-request-header:request_header_tag')
    // We don't inject a matching response header tag here since it would be always on and show up in all the tests
  }

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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, tagQueryString, url)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
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

  // IBM JVM has different protocol support for TLS
  @Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
  def "basic secure #method request"() {
    given:
    assumeTrue(testSecure())

    when:
    def status = doRequest(method, url)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method << BODY_METHODS
    body = (1..10000).join(" ")
  }

  @Flaky(suites = ["ApacheHttpAsyncClient5Test"])
  def "server error request with parent"() {
    setup:
    def uri = server.address.resolve("/error")

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, uri)
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method | _
    "GET"  | _
    "POST" | _
  }

  @Flaky(suites = ["ApacheHttpAsyncClient5Test"])
  def "client error request with parent"() {
    setup:
    def uri = server.address.resolve("/secured")

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, uri)
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, true)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "HEAD"
  }

  @Flaky(suites = ["ApacheHttpAsyncClient5Test"])
  def "trace request without propagation"() {
    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"])
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "GET"
    renameService << [false, true]
  }

  @Flaky(value = 'Futures timed out after [1 second]', suites = ['PlayWSClientTest'])
  def "trace request with callback and parent"() {
    given:
    def method = 'GET'
    assumeTrue(testCallbackWithParent())

    when:
    def status = runUnderTrace("parent") {
      doRequest(method, server.address.resolve("/success"), ["is-dd-server": "false"], "") {
        runUnderTrace("child") {
          blockUntilChildSpansFinished(1)
        }
      }
    }
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(3) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

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

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(3) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).last())
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
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
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(1) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, uri)
      }
    }

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method = "HEAD"
  }

  def "test request header #header tag mapping"() {
    when:
    def url = server.address.resolve("/success")
    def status = doRequest(method, url, [(header): value])
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url, status, false, null, false, tags)
      }
      server.distributedRequestTrace(it, trace(0).last(), tags)
    }
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method | header                           | value | tags
    'GET'  | 'X-Datadog-Test-Both-Header'     | 'foo' | [ 'both_header_tag': 'foo' ]
    'GET'  | 'X-Datadog-Test-Request-Header'  | 'bar' | [ 'request_header_tag': 'bar' ]
  }

  def "test response header #header tag mapping"() {
    when:
    injectSysConfig(RESPONSE_HEADER_TAGS, "$header:$mapping")
    def url = server.address.resolve("/respond-with-header")
    def status = doRequest(method, url)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url, status, false, null, false, tags)
      }
      server.distributedRequestTrace(it, trace(0).last())
    }
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method | header                           | mapping               | tags
    'GET'  | 'X-Datadog-Test-Response-Header' | 'response_header_tag' | [ 'response_header_tag': 'baz' ]
  }

  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void clientSpan(
    TraceAssert trace,
    Object parentSpan,
    String method = "GET",
    boolean renameService = false,
    boolean tagQueryString = false,
    URI uri = server.address.resolve("/success"),
    Integer status = 200,
    boolean error = false,
    Throwable exception = null,
    boolean ignorePeer = false,
    Map<String, Serializable> extraTags = null) {

    def expectedQuery = tagQueryString ? uri.query : null
    def expectedUrl = URIUtils.buildURL(uri.scheme, uri.host, uri.port, uri.path)
    if (expectedQuery != null && !expectedQuery.empty) {
      expectedUrl = "$expectedUrl?$expectedQuery"
    }
    trace.span {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      if (renameService) {
        serviceName uri.host
      }
      operationName operation()
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
        "$Tags.HTTP_URL" expectedUrl
        "$Tags.HTTP_METHOD" method
        if (status) {
          "$Tags.HTTP_STATUS" status
        }
        if (tagQueryString) {
          "$DDTags.HTTP_QUERY" expectedQuery
          "$DDTags.HTTP_FRAGMENT" { it == null || it == uri.fragment } // Optional
        }
        if ({ isDataStreamsEnabled() }) {
          "$DDTags.PATHWAY_HASH" { String }
        }
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        peerServiceFrom(Tags.PEER_HOSTNAME)
        defaultTags()
        if (extraTags) {
          it.addTags(extraTags)
        }
      }
    }
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

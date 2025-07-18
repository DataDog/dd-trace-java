import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.core.DDSpan
import datadog.trace.core.datastreams.StatsGroup

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
class HttpUrlConnectionErrorReportingTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV0 {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('dd.trace.http-url-connection.errors.enabled', 'true')
  }


  @Override
  boolean hasExtraErrorInformation(){
    true
  }

  def "client error request with parent with error reporting"() {
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
        it.span(it.nextSpanId()){
          parent()
          hasServiceName()
          operationName "parent"
          resourceName "parent"
          errored false
          tags {
            defaultTags()
          }
        }
        clientSpanError(it, span(0), method, false, false, uri, 401, true)
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
  def "server error request with parent with error reporting"() {
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
        clientSpanError(it, span(0), method, false, false, uri, 500, false) // error.
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


  void clientSpanError(
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
      errored true
      measured true
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" { it == uri.host || ignorePeer }
        "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" || ignorePeer } // Optional
        "$Tags.PEER_PORT" { it == null || it == uri.port || it == proxy.port || it == 443 || ignorePeer }
        "$Tags.HTTP_URL" expectedUrl
        "$Tags.HTTP_METHOD" method
        "error.message" String
        "error.type" IOException.name
        "error.stack" String
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

}

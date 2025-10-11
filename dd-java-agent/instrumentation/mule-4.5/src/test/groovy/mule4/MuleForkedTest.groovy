package mule4

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static mule4.MuleTestApplicationConstants.TEST_APPLICATION_JAR
import static mule4.MuleTestApplicationConstants.TEST_APPLICATION_NAME
import static org.mule.runtime.api.util.MuleTestUtil.muleSpan

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.AutoCleanup
import spock.lang.Shared

class MuleForkedTest extends WithHttpServer<MuleTestContainer> {

  // TODO since mule uses reactor core, things sometime propagate to places where they're not closed
  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("integration.mule.enabled", "true")
  }

  @AutoCleanup
  @Shared
  def requestServer = httpServer {
    handlers {
      prefix("remote-client-request") {
        String msg = "Hello Client."
        response.status(200).send(msg)
      }
      prefix("remote-pfe-request") {
        String name = request.getParameter("name")
        String msg = "\"Hello $name\""
        String contentType = "application/json"
        response.status(200).sendWithType(contentType, msg)
      }
    }
  }

  @Shared
  Properties buildProperties = {
    Properties props = new Properties()
    props.load(this.class.getResourceAsStream("/test-build.properties"))
    return props
  }.call()

  @Override
  MuleTestContainer startServer(int port) {
    File muleBase = new File(String.valueOf(buildProperties.get("mule.base")))
    MuleTestContainer container = new MuleTestContainer(muleBase)
    container.start()
    def appProperties = new Properties()
    def reqUri = requestServer.address
    ["test.server.port"     : "$port", "test.server.host": "localhost", "test.request.port": "${reqUri.port}",
      "test.request.host"    : "${reqUri.host}", "test.request.path": "/remote-client-request",
      "test.request.pfe_path": "/remote-pfe-request"].each {
      // Force cast GStringImpl to String since Mule code does String casts of some properties
      appProperties.put((String) it.key, (String) it.value)
    }
    def app = new URI("file:" + new File(String.valueOf(buildProperties.get(TEST_APPLICATION_JAR))).canonicalPath)
    container.deploy(app, appProperties)
    return container
  }

  @Override
  void stopServer(MuleTestContainer container) {
    if (container != null) {
      container.undeploy(String.valueOf(buildProperties.get(TEST_APPLICATION_NAME)))
      container.stop()
    }
  }

  def "test mule client remote request"() {
    setup:
    def url = HttpUrl.get(address.resolve("/client-request")).newBuilder().build()
    def request = new Request.Builder().url(url).method("GET", null).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == "Hello Client."
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span {
          operationName operation()
          resourceName "GET /client-request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "grizzly-filterchain-server"
            "$Tags.SPAN_KIND" "server"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_URL" "${address.resolve("/client-request")}"
            "$Tags.HTTP_HOSTNAME" address.host
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.PEER_PORT" { true } // is this really the best way to ignore tags?
            defaultTags()
          }
        }
        muleSpan(it, "mule:flow", "MuleHttpServerClientTestFlow")
        muleSpan(it, "http:request", "Http Request")
        span {
          childOfPrevious()
          operationName "http.request"
          resourceName "GET /remote-client-request"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            "$Tags.COMPONENT" "grizzly-http-async-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_URL" "${requestServer.address.resolve("/remote-client-request")}"
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" { Integer }
            defaultTags()
          }
        }
      }
    }
  }

  def "test parallel for each"() {
    setup:
    def names = ["Alyssa", "Ben", "Cy", "Eva", "Lem", "Louis"]
    def jsonAdapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(List, String))
    def input = jsonAdapter.toJson(names)
    def output = names.collect { name -> "Hello $name" }
    def url = HttpUrl.get(address.resolve("/pfe-request")).newBuilder().build()
    def body = RequestBody.create(MediaType.get("application/json"), input)
    def request = new Request.Builder().url(url).method("PUT", body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    jsonAdapter.fromJson(response.body().string()) == output

    assertTraces(1) {
      trace(4 + 3 * names.size(), new TreeComparator(trace(0))) { traceAssert ->
        span {
          operationName operation()
          resourceName "PUT /pfe-request"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "grizzly-filterchain-server"
            "$Tags.SPAN_KIND" "server"
            "$Tags.HTTP_METHOD" "PUT"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_URL" "${address.resolve("/pfe-request")}"
            "$Tags.HTTP_HOSTNAME" address.host
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.PEER_PORT" { Integer }
            defaultTags()
          }
        }
        def flowParent = muleSpan(traceAssert, "mule:flow", "MulePFETestFlow")
        def foreachParent = muleSpan(traceAssert, "mule:parallel-foreach", "PFE", flowParent)
        muleSpan(traceAssert, "mule:set-payload", "PFE Set Payload", flowParent)
        def iterationParents = []
        for (def pos = 1; pos <= names.size(); pos++) {
          iterationParents += muleSpan(traceAssert, "mule:parallel-foreach:iteration", "PFE", foreachParent)
        }
        def requestParents =[]
        iterationParents.each { parent ->
          requestParents  += muleSpan(traceAssert, "http:request", "PFE Request", parent)
        }
        requestParents.each {parent ->
          traceAssert.span {
            childOf parent
            operationName "http.request"
            resourceName "GET /remote-pfe-request"
            spanType DDSpanTypes.HTTP_CLIENT
            tags {
              "$Tags.COMPONENT" "grizzly-http-async-client"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.HTTP_METHOD" "GET"
              "$Tags.HTTP_STATUS" 200
              "$Tags.PEER_HOSTNAME" "localhost"
              "$Tags.PEER_PORT" { true } // is this really the best way to ignore tags?
              urlTags("${requestServer.address.resolve("/remote-pfe-request")}", ExpectedQueryParams.getExpectedQueryParams("Mule"))
              defaultTags()
            }
          }
        }
      }
    }
  }

  /**
   * Sorts the spans by level in the trace (how many parents).
   * If in the same level, the one with lower parent it will come first.
   */
  private static class TreeComparator implements Comparator<DDSpan> {
    private final Map<DDSpan, Long> levels
    private final Map<Long, DDSpan> traceMap

    TreeComparator(List<DDSpan> trace) {
      traceMap =  trace.collectEntries { [(it.spanId): it] }
      levels = trace.collectEntries({
        [(it): walkUp(traceMap, it, 0)]
      })
    }

    @Override
    int compare(DDSpan o1, DDSpan o2) {
      def len = levels[o1] <=> levels[o2]
      // if they are not on the same tree level, take the one with shortest path to the root
      if (len != 0) {
        return len
      }
      if (o1.parentId == o2.parentId) {
        return o1.spanId <=> o2.spanId
      }
      return compare(traceMap.get(o1.parentId), traceMap.get(o2.parentId))
    }

    def walkUp(Map<Long, DDSpan> traceMap, DDSpan span, int size) {
      if (span.parentId == 0) {
        return size
      }
      return walkUp(traceMap, traceMap.get(span.parentId), size + 1)
    }
  }

  //test for v1 will be added once grizzly will support v1 naming
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "grizzly.request"
  }
}

package mule4

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static mule4.MuleTestApplicationConstants.*

class MuleForkedTest extends WithHttpServer<MuleTestContainer> {

  // TODO since mule uses reactor core, things sometime propagate to places where they're not closed
  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("integration.grizzly-filterchain.enabled", "true")
    injectSysConfig("integration.mule.enabled", "true")
    injectSysConfig("integration.grizzly-client.enabled", "true")
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
    ["test.server.port": "$port", "test.server.host": "localhost", "test.request.port": "${reqUri.port}",
      "test.request.host": "${reqUri.host}", "test.request.path": "/remote-client-request",
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
    container.undeploy(String.valueOf(buildProperties.get(TEST_APPLICATION_NAME)))
    container.stop()
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
      trace(2) {
        span(0) {
          operationName "grizzly.request"
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
        span(1) {
          childOf(span(0))
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
            "$Tags.PEER_PORT" { true } // is this really the best way to ignore tags?
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
    def output = names.collect {name -> "Hello $name" }
    def url = HttpUrl.get(address.resolve("/pfe-request")).newBuilder().build()
    def body = RequestBody.create(MediaType.get("application/json"), input)
    def request = new Request.Builder().url(url).method("PUT", body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 200
    jsonAdapter.fromJson(response.body().string()) == output
    assertTraces(1) {
      trace(1 + names.size()) { traceAssert ->
        traceAssert.span(0) {
          operationName "grizzly.request"
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
            "$Tags.PEER_PORT" { true } // is this really the best way to ignore tags?
            defaultTags()
          }
        }
        for (def pos = 1; pos <= names.size(); pos++) {
          traceAssert.span(pos) {
            childOf(span(0))
            operationName "http.request"
            resourceName "GET /remote-pfe-request"
            spanType DDSpanTypes.HTTP_CLIENT
            tags {
              "$Tags.COMPONENT" "grizzly-http-async-client"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.HTTP_METHOD" "GET"
              "$Tags.HTTP_STATUS" 200
              "$Tags.HTTP_URL" "${requestServer.address.resolve("/remote-pfe-request")}"
              "$Tags.PEER_HOSTNAME" "localhost"
              "$Tags.PEER_PORT" { true } // is this really the best way to ignore tags?
              defaultTags()
            }
          }
        }
      }
    }
  }
}

package datadog.trace.api.naming

import datadog.trace.api.naming.v0.ClientNamingV0
import datadog.trace.api.naming.v0.ServerNamingV0
import datadog.trace.test.util.DDSpecification

/**
 * Certifies the fix for APPSEC-61670: duplicate endpoints in the APM Endpoints view caused by
 * common HTTP client libraries (Apache HttpClient, HttpURLConnection, etc.) sharing the generic
 * "http.request" operation name, which was ambiguous and confused the Endpoints view into
 * displaying them alongside "servlet.request" server spans as separate "endpoints" for the same
 * URL path.
 *
 * After the fix, each well-known HTTP client library has its own distinct operation name,
 * consistent with the existing pattern for OkHttp ("okhttp.request"), Play-WS ("play-ws.request"),
 * and Netty client ("netty.client.request").
 */
class ClientNamingV0OperationNameTest extends DDSpecification {

  def clientNaming = new ClientNamingV0()
  def serverNaming = new ServerNamingV0()

  def "well-known HTTP client components have distinct operation names — not the generic http.request"() {
    expect:
    clientNaming.operationForComponent(component) == expectedOperation

    where:
    component                  | expectedOperation
    "apache-httpclient"        | "apache-httpclient.request"
    "apache-httpclient5"       | "apache-httpclient.request"
    "apache-httpasyncclient"   | "apache-httpasyncclient.request"
    "commons-http-client"      | "commons-http-client.request"
    "google-http-client"       | "google-http-client.request"
    "http-url-connection"      | "http-url-connection.request"
    "java-http-client"         | "java-http-client.request"
    "grizzly-http-async-client"| "grizzly-http-async-client.request"
    "spring-webflux-client"    | "spring-webflux-client.request"
    "jetty-client"             | "jetty-client.request"
  }

  def "pre-existing explicit entries are not affected"() {
    expect:
    clientNaming.operationForComponent(component) == expectedOperation

    where:
    component          | expectedOperation
    "okhttp"           | "okhttp.request"
    "play-ws"          | "play-ws.request"
    "netty-client"     | "netty.client.request"
    "akka-http-client" | "akka-http.client.request"
    "pekko-http-client"| "pekko-http.client.request"
    "jax-rs.client"    | "jax-rs.client.call"
  }

  def "unknown components still fall back to http.request"() {
    expect:
    clientNaming.operationForComponent("some-custom-http-client") == "http.request"
    clientNaming.operationForComponent("unknown") == "http.request"
  }

  def "APM Endpoints view: client and server spans for the same URL produce distinct aggregation keys"() {
    given: "the Endpoints view groups spans by (operationName, httpMethod, resourcePath)"
    def method = "GET"
    def path = "/api/users"

    and: "the server-side operation name produced by a servlet span"
    def serverKey = [serverNaming.operationForComponent("java-web-servlet"), method, path]

    and: "the client-side operation name produced by the HTTP client library"
    def clientKey = [clientNaming.operationForComponent(component), method, path]

    expect: "the two keys are different — no duplicate row appears in the Endpoints view"
    clientKey != serverKey

    where: "every fixed HTTP client component is tested"
    component << [
      "apache-httpclient",
      "apache-httpclient5",
      "apache-httpasyncclient",
      "commons-http-client",
      "google-http-client",
      "http-url-connection",
      "java-http-client",
      "grizzly-http-async-client",
      "spring-webflux-client",
      "jetty-client",
    ]
  }

  def "fixed client operation names do not collide with servlet.request server operation"() {
    given:
    def servletOpName = serverNaming.operationForComponent("java-web-servlet")

    expect: "no fixed client operation name equals servlet.request"
    clientNaming.operationForComponent(component) != servletOpName

    where:
    component << [
      "apache-httpclient",
      "apache-httpclient5",
      "apache-httpasyncclient",
      "commons-http-client",
      "google-http-client",
      "http-url-connection",
      "java-http-client",
      "grizzly-http-async-client",
      "spring-webflux-client",
      "jetty-client",
    ]
  }

  def "fixed client operation names do not collide with server operationForProtocol(http)"() {
    given:
    def httpProtocolOpName = serverNaming.operationForProtocol("http")

    expect: "no fixed client operation name equals http.request (the server protocol op)"
    clientNaming.operationForComponent(component) != httpProtocolOpName

    where:
    component << [
      "apache-httpclient",
      "apache-httpclient5",
      "apache-httpasyncclient",
      "commons-http-client",
      "google-http-client",
      "http-url-connection",
      "java-http-client",
      "grizzly-http-async-client",
      "spring-webflux-client",
      "jetty-client",
    ]
  }
}

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class SprayServerTest extends HttpServerTest<SprayHttpTestWebServer> {

  @Override
  HttpServer server() {
    SprayHttpTestWebServer server = new SprayHttpTestWebServer()
    server.start()
    return server
  }

  @Override
  void stopServer(SprayHttpTestWebServer sprayHttpTestWebServer) {
    sprayHttpTestWebServer.stop()
  }

  @Override
  String component() {
    return SprayHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return SprayHttpServerDecorator.DECORATE.SPRAY_HTTP_REQUEST
  }

  @Override
  boolean testExceptionBody() {
    // Todo: Response{protocol=http/1.1, code=500, message=Internal Server Error, url=...}
    false
  }

  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        // todo:
        //  "$Tags.PEER_PORT" Integer
        //  "$Tags.PEER_HOST_IPV4" { it == (endpoint == FORWARDED ? endpoint.body : "127.0.0.1") }
        "$Tags.HTTP_STATUS" endpoint.status
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }
}

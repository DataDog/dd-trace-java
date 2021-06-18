import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.micronaut.MicronautDecorator
import test.MicronautServer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION

class MicronautTest extends HttpServerTest<Object> {

  @Override
  HttpServer server() {
    return new MicronautServer()
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.request"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  boolean tagServerSpanWithRoute(ServerEndpoint endpoint) {
    endpoint != NOT_FOUND
  }

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    boolean tagServerSpanWithRoute = tagServerSpanWithRoute(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.resource(method, address, testPathParam())
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
        "$Tags.PEER_PORT" Integer
        "$Tags.PEER_HOST_IPV4" { it == "127.0.0.1" || (endpoint == FORWARDED && it == endpoint.body) }
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" endpoint.status
        if (endpoint == FORWARDED) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        if (tagServerSpanWithRoute) {
          "$Tags.HTTP_ROUTE" String
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "micronaut-controller"
      resourceName {
        it == "TestController.${endpoint.name().toLowerCase()}" || endpoint == NOT_FOUND && it == "404"
      }
      spanType DDSpanTypes.HTTP_SERVER
      errored (endpoint == EXCEPTION || endpoint == ERROR)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" MicronautDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}

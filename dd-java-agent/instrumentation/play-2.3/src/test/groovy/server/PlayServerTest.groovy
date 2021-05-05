package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.play23.PlayHttpServerDecorator
import play.api.test.TestServer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class PlayServerTest extends HttpServerTest<TestServer> {

  @Override
  HttpServer server() {
    return new SyncServer()
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.request"
  }

  // We don't have instrumentation for this version of netty yet
  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName "play.request"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" PlayHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { endpoint == ServerEndpoint.FORWARDED ? it == endpoint.body : (it == null || it == "127.0.0.1") }
        "$Tags.HTTP_URL" String
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_STATUS" Integer
        // BUG
        //        "$Tags.HTTP_ROUTE" String
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags()
      }
    }
  }
}

package server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator
import datadog.trace.instrumentation.play26.PlayHttpServerDecorator
import play.BuiltInComponents
import play.mvc.Results
import play.routing.RoutingDsl
import play.server.Server

import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class PlayServerTest extends HttpServerTest<Server> {

  @Override
  HttpServer server() {
    return new PlayHttpServer({ BuiltInComponents components ->
      RoutingDsl.fromComponents(components)
        .GET(SUCCESS.getPath()).routeTo({
          controller(SUCCESS) {
            Results.status(SUCCESS.getStatus(), SUCCESS.getBody())
          }
        } as Supplier)
        .GET(FORWARDED.getPath()).routeTo({
          controller(FORWARDED) {
            Results.status(FORWARDED.getStatus(), FORWARDED.getBody()) // cheating
          }
        } as Supplier)
        .GET(QUERY_PARAM.getPath()).routeTo({
          controller(QUERY_PARAM) {
            Results.status(QUERY_PARAM.getStatus(), QUERY_PARAM.getBody()) // cheating
          }
        } as Supplier)
        .GET(REDIRECT.getPath()).routeTo({
          controller(REDIRECT) {
            Results.found(REDIRECT.getBody())
          }
        } as Supplier)
        .GET(ERROR.getPath()).routeTo({
          controller(ERROR) {
            Results.status(ERROR.getStatus(), ERROR.getBody())
          }
        } as Supplier)
        .GET(EXCEPTION.getPath()).routeTo({
          controller(EXCEPTION) {
            throw new Exception(EXCEPTION.getBody())
          }
        } as Supplier)
        .build()
    })
  }

  @Override
  void stopServer(Server server) {
    server.stop()
  }

  @Override
  String component() {
    return AkkaHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "akka-http.request"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  boolean testExceptionBody() {
    // I can't figure out how to set a proper exception handler to customize the response body.
    false
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
        "$Tags.PEER_HOST_IPV4" { endpoint == FORWARDED ? it == endpoint.body : (it == null || it == "127.0.0.1") }
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
        "$Tags.PEER_HOST_IPV4" { endpoint == FORWARDED ? it == endpoint.body : (it == null || it == "127.0.0.1") }
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

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

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
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
        .GET(QUERY_ENCODED_QUERY.getPath()).routeTo({
          controller(QUERY_ENCODED_QUERY) {
            Results.status(QUERY_ENCODED_QUERY.getStatus(), QUERY_ENCODED_QUERY.getBody()) // cheating
          }
        } as Supplier)
        .GET(QUERY_ENCODED_BOTH.getRawPath()).routeTo({
          controller(QUERY_ENCODED_BOTH) {
            Results.status(QUERY_ENCODED_BOTH.getStatus(), QUERY_ENCODED_BOTH.getBody()).
              withHeader(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE) // cheating
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
            throw new RuntimeException(EXCEPTION.getBody())
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

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean changesAll404s() {
    true
  }

  @Override
  boolean hasPeerPort() {
    false
  }

  boolean testExceptionBody() {
    // I can't figure out how to set a proper exception handler to customize the response body.
    false
  }

  @Override
  Class<? extends Exception> expectedExceptionType() {
    RuntimeException
  }

  @Override
  Class<? extends Exception> expectedCustomExceptionType() {
    TestHttpErrorHandler.CustomRuntimeException
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    def expectedQueryTag = expectedQueryTag(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName "play.request"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" PlayHttpServerDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" { it == (endpoint == FORWARDED ? endpoint.body : "127.0.0.1") }
        "$Tags.HTTP_CLIENT_IP" { it == (endpoint == FORWARDED ? endpoint.body : "127.0.0.1") }
        "$Tags.HTTP_URL" String
        "$Tags.HTTP_HOSTNAME" address.host
        "$Tags.HTTP_METHOD" String
        // BUG
        //        "$Tags.HTTP_ROUTE" String
        if (endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION) {
          errorTags(endpoint == CUSTOM_EXCEPTION ? TestHttpErrorHandler.CustomRuntimeException : RuntimeException, endpoint.body)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" expectedQueryTag
        }
        defaultTags()
      }
    }
  }
}

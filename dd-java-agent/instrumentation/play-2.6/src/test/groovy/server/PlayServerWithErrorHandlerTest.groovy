package server

import datadog.trace.agent.test.base.HttpServer
import play.BuiltInComponents
import play.mvc.Results
import play.routing.RoutingDsl

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
import static org.junit.Assume.assumeTrue

class PlayServerWithErrorHandlerTest extends PlayServerTest {
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
        .GET(CUSTOM_EXCEPTION.getPath()).routeTo({
          controller(CUSTOM_EXCEPTION) {
            throw new TestHttpErrorHandler.CustomRuntimeException(CUSTOM_EXCEPTION.getBody())
          }
        } as Supplier)
        .build()
    }, new TestHttpErrorHandler())
  }

  @Override
  boolean testExceptionBody() {
    true
  }

  def "test exception with custom status"() {
    setup:
    assumeTrue(testException())
    def request = request(CUSTOM_EXCEPTION, method, body).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == CUSTOM_EXCEPTION.status
    if (testExceptionBody()) {
      assert response.body().string() == CUSTOM_EXCEPTION.body
    }

    and:
    assertTraces(1) {
      trace(spanCount(CUSTOM_EXCEPTION)) {
        sortSpansByStart()
        serverSpan(it, null, null, method, CUSTOM_EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, CUSTOM_EXCEPTION)
        }
        controllerSpan(it, CUSTOM_EXCEPTION)
        if (hasResponseSpan(CUSTOM_EXCEPTION)) {
          responseSpan(it, CUSTOM_EXCEPTION)
        }
      }
    }

    where:
    method = "GET"
    body = null
  }
}

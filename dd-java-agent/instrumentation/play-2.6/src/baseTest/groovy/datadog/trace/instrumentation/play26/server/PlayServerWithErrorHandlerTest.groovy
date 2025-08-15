package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.base.HttpServer
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION

class PlayServerWithErrorHandlerTest extends PlayServerTest {
  @Override
  HttpServer server() {
    new PlayHttpServer(PlayRouters.&sync, new TestHttpErrorHandler())
  }

  @Override
  boolean testExceptionBody() {
    true
  }

  @IgnoreIf({ !instance.testException() })
  def "test exception with custom status"() {
    setup:
    def request = request(CUSTOM_EXCEPTION, 'GET', null).build()
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
        serverSpan(it, null, null, 'GET', CUSTOM_EXCEPTION)
        if (hasHandlerSpan()) {
          handlerSpan(it, CUSTOM_EXCEPTION)
        }
        controllerSpan(it, CUSTOM_EXCEPTION)
        if (hasResponseSpan(CUSTOM_EXCEPTION)) {
          responseSpan(it, CUSTOM_EXCEPTION)
        }
      }
    }
  }
}

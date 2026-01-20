package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.play26.PlayHttpServerDecorator
import groovy.transform.CompileStatic
import okhttp3.MediaType
import okhttp3.RequestBody
import play.server.Server
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_XML
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class AbstractPlayServerTest extends HttpServerTest<Server> {

  @Override
  @CompileStatic
  HttpServer server() {
    new PlayHttpServer(PlayRouters.&sync)
  }

  @Override
  void stopServer(Server server) {
    server.stop()
  }

  @Override
  String component() {
    'akka-http-server'
  }

  @Override
  String expectedOperationName() {
    'akka-http.request'
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

  boolean testExceptionBody() {
    // I can't figure out how to set a proper exception handler to customize the response body.
    false
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean isRequestBodyNoStreaming() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testResponseBodyJson() {
    true
  }

  @Override
  String testPathParam() {
    '/path/?/param'
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    ['0': 123]
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
        "$Tags.PEER_HOST_IPV4" '127.0.0.1'
        "$Tags.HTTP_CLIENT_IP" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
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

  boolean testBodyXml() {
    true
  }

  @IgnoreIf({ !instance.testBodyXml() })
  def 'test instrumentation gateway xml request body'() {
    setup:
    def request = request(
      BODY_XML, 'POST',
      RequestBody.create(MediaType.get('text/xml'), '<foo attr="attr_value">mytext<bar></bar></foo>'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    String body = response.body().charStream().text


    expect:
    body == BODY_XML.body || body == '<?xml version="1.0" encoding="UTF-8"?><foo attr="attr_value">mytext<bar/></foo>'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[[children:[mytext, [:]], attributes:[attr:attr_value]]]'
    }
  }
}

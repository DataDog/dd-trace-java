package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.play24.PlayHttpServerDecorator
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import okhttp3.MediaType
import okhttp3.RequestBody
import play.server.Server

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static org.junit.jupiter.api.Assumptions.assumeTrue

class PlayServerTest extends HttpServerTest<Server> {

  @Override
  @CompileStatic
  HttpServer server() {
    new PlayHttpServer(PlayRouters.sync())
  }

  @Override
  void stopServer(Server server) {
    server.stop()
  }

  @Override
  String component() {
    'netty'
  }

  @Override
  String expectedOperationName() {
    'netty.request'
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
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilesContent() {
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

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching StatusHeaderSendJsonAdvice (Java routers) or ResultsStatusApplyAdvice (Scala routers).
   */
  def 'test blocking on json response body'() {
    setup:
    assumeTrue(testBlockingOnResponse() && testResponseBodyJson())
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(MediaType.get('application/json'), JsonOutput.toJson([a: 'x'])))
      .header(IG_BLOCK_RESPONSE_HEADER, 'body')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)
    def rootSpan = TEST_WRITER.get(0).find {
      it.parentId == 0
    }
    rootSpan != null
    rootSpan.tags['http.status_code'] == 413
    rootSpan.tags['appsec.blocked'] == 'true'
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
        "$Tags.NETWORK_CLIENT_IP" '127.0.0.1'
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

package server

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static org.junit.jupiter.api.Assumptions.assumeTrue
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.Config
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator
import io.vertx.core.AbstractVerticle
import io.vertx.core.Vertx

class VertxHttpServerForkedTest extends HttpServerTest<Vertx> {
  @Override
  HttpServer server() {
    return new VertxServer(verticle(), routerBasePath())
  }

  protected Class<AbstractVerticle> verticle() {
    VertxTestServer
  }

  String routerBasePath() {
    return "/"
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    "netty.request"
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  String testPathParam() {
    routerBasePath() + "path/:id/param"
  }

  @Override
  boolean testExceptionBody() {
    // Vertx wraps the exception
    false
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [id: '123']
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testResponseBodyJson() {
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
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilesContent() {
    true
  }

  @Override
  boolean testBodyFilesContentOrdering() {
    false
  }

  // fileUploads() returns a HashSet in Vert.x: check count instead of which specific file is excluded
  def 'test instrumentation gateway file upload content max files limit count'() {
    setup:
    assumeTrue(testBodyFilesContent())
    def maxFilesToInspect = Config.get().getAppSecMaxFileContentCount()
    def bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM)
    (1..maxFilesToInspect + 1).each { i ->
      bodyBuilder.addFormDataPart("file${i}", "file${i}.bin",
        RequestBody.create(MediaType.parse('application/octet-stream'), "content_of_file_${i}"))
    }
    def httpRequest = request(BODY_MULTIPART, 'POST', bodyBuilder.build()).build()
    def response = client.newCall(httpRequest).execute()

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any { span ->
      def tag = span.getTag('request.body.files_content') as String
      tag != null && tag.count('content_of_file_') == maxFilesToInspect
    }

    cleanup:
    response.close()
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
  boolean isRequestBodyNoStreaming() {
    true
  }

  @Override
  Class<? extends Exception> expectedExceptionType() {
    return RuntimeException
  }

  boolean testExceptionTag() {
    true
  }

  @Override
  boolean hasDecodedResource() {
    return false
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    if (endpoint == NOT_FOUND) {
      return super.spanCount(endpoint) - 1
    }
    return super.spanCount(endpoint)
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case LOGIN:
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      default:
        return routerBasePath() + endpoint.relativePath()
    }
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint == NOT_FOUND) {
      return
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "vertx.route-handler"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == ERROR || endpoint == EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" VertxDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION && this.testExceptionTag()) {
          errorTags(RuntimeException, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}

class VertxHttpServerWorkerForkedTest extends VertxHttpServerForkedTest {
  @Override
  HttpServer server() {
    return new VertxServer(verticle(), routerBasePath(), true)
  }

  def 'test blocking of JSON request body finishes route handler span'() {
    setup:
    // VertxTestServer handles BODY_JSON by calling ctx.body().asJsonObject().
    // The IG_BODY_CONVERTED_HEADER is consumed by HttpServerTest's AppSec test callback, which
    // returns a RequestBlockingAction from requestBodyProcessed() when that JSON body is converted.
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(MediaType.get('application/json'), '{"a": "x"}'))
      .header(IG_BODY_CONVERTED_HEADER, 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan
    // The client receiving a 413 only proves the blocking response was committed.
    // We want to make sure that a BlockingException does now abort the worker route handler
    // before the vertx.route-handler span has been finished (which would leave it dangling)
    TEST_WRITER.waitForTraces(1)
  }
}

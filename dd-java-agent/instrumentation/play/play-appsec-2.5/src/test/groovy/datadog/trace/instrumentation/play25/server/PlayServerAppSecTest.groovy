package datadog.trace.instrumentation.play25.server

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest.RbaFlow
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.instrumentation.play25.PlayRoutersScala
import datadog.trace.test.util.ExcludeInheritedFeatures
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiFunction

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.api.gateway.Events.EVENTS
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get
import static org.junit.jupiter.api.Assumptions.assumeTrue

@ExcludeInheritedFeatures
class PlayServerAppSecTest extends PlayServerTest {

  /**
   * Message of the BlockingException thrown by the advice expected to block BODY_JSON: Java routers
   * call Results.status(int, JsonNode) -> StatusHeader.sendJson(JsonNode, String).
   */
  String expectedBlockingAdviceMessage() {
    'Blocked request (for StatusHeader/sendJson)'
  }

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching StatusHeaderSendJsonAdvice (Java routers) or ResultsStatusApplyAdvice (Scala routers).
   * The controller span error pins which advice threw, so the block cannot come from another path.
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
    def controllerSpan = TEST_WRITER.get(0).find {
      it.operationName.toString() == 'controller'
    }
    controllerSpan != null
    controllerSpan.error
    controllerSpan.tags['error.type'] == BlockingException.name
    controllerSpan.tags['error.message'] == expectedBlockingAdviceMessage()
  }

  /**
   * Uploads a real multipart file part (no form fields, so the multipartFormData requestBodyProcessed
   * branch is skipped) and blocks from the filenames or the files content callback. The BlockingException
   * message pins which BodyParserHelpers branch committed the block.
   */
  def 'test blocking on multipart file upload #variant'() {
    setup:
    installBlockingFileCallbacks()
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('file', filename, RequestBody.create(MediaType.parse('application/octet-stream'), content))
      .build()
    def request = request(BODY_MULTIPART, 'POST', body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    assertBlockedByBodyParser(response, expectedMessage)

    cleanup:
    restoreFileCallbacks()

    where:
    variant         | filename         | content           | expectedMessage
    'filenames'     | BLOCKED_FILENAME | 'file content'    | 'Blocked request (multipart file upload)'
    'files content' | 'test.bin'       | BLOCKED_CONTENT   | 'Blocked request (multipart file upload content)'
  }

  boolean assertBlockedByBodyParser(Response response, String expectedMessage) {
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    assert response.code() == 413
    assert response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)

    def spans = TEST_WRITER.flatten()
    assert spans.find {
      it.parentId == 0 && it.tags['http.status_code'] == 413 && it.tags['appsec.blocked'] == 'true'
    } != null
    assert spans.find {
      it.error && it.tags['error.type'] == BlockingException.name && it.tags['error.message'] == expectedMessage
    } != null
    true
  }

  static final String BLOCKED_FILENAME = 'block-me.php'
  static final String BLOCKED_CONTENT = 'block-me-content'

  /**
   * The shared HttpServerTest filenames/files content callbacks never block, and registering a
   * second callback for the same event throws, so this spec's (per-spec) gateway slots are cleared
   * and replaced with callbacks that block only for the BLOCKED_* markers.
   */
  static void installBlockingFileCallbacks() {
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(EVENTS.requestFilesFilenames())
    ss.registerCallback(EVENTS.requestFilesFilenames(), blockingIf { List<String> names -> names.contains(BLOCKED_FILENAME) })
    ss.reset(EVENTS.requestFilesContent())
    ss.registerCallback(EVENTS.requestFilesContent(), blockingIf { List<String> contents -> contents.contains(BLOCKED_CONTENT) })
  }

  /** Restores the shared HttpServerTest filenames/files content callbacks replaced by installBlockingFileCallbacks(). */
  void restoreFileCallbacks() {
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(EVENTS.requestFilesFilenames())
    ss.registerCallback(EVENTS.requestFilesFilenames(), ({ RequestContext rqCtxt, List<String> filenames ->
      rqCtxt.traceSegment.setTagTop('request.body.filenames', filenames as String)
      def context = rqCtxt.getData(RequestContextSlot.APPSEC)
      context.uploadedFilenames = filenames
      context.uploadedFilenamesCallCount++
      rqCtxt.traceSegment.setTagTop('_dd.appsec.filenames.cb.calls', context.uploadedFilenamesCallCount)
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>))
    ss.reset(EVENTS.requestFilesContent())
    ss.registerCallback(EVENTS.requestFilesContent(), ({ RequestContext rqCtxt, List<String> contents ->
      rqCtxt.traceSegment.setTagTop('request.body.files_content', contents as String)
      def context = rqCtxt.getData(RequestContextSlot.APPSEC)
      context.uploadedFilesContent = contents
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>))
  }

  private static BiFunction<RequestContext, List<String>, Flow<Void>> blockingIf(Closure<Boolean> shouldBlock) {
    ({ RequestContext ctx, List<String> values ->
      shouldBlock(values)
        ? new RbaFlow(new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON))
        : Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>)
  }
}

class PlayScalaAsyncServerAppSecTest extends PlayServerAppSecTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  @CompileStatic
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    new PlayHttpServer(PlayRoutersScala.async(executor).asJava())
  }

  /** Scala routers call Results.Ok(JsValue) -> Results$Status.apply(JsValue, Writeable). */
  @Override
  String expectedBlockingAdviceMessage() {
    'Blocked request (for Results$Status/apply)'
  }
}

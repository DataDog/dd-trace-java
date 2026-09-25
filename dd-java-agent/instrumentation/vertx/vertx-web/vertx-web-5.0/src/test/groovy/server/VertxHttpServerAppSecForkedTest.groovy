package server

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest.RbaFlow
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.test.util.ExcludeInheritedFeatures
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody

import java.util.function.BiFunction

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SESSION_ID
import static datadog.trace.api.gateway.Events.EVENTS
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.get

@ExcludeInheritedFeatures
class VertxHttpServerAppSecForkedTest extends VertxHttpServerForkedTest {

  /**
   * Blocks from the JSON response body callback only ('body' is ignored by responseHeaderDone),
   * reaching RoutingContextJsonResponseAdvice via ctx.json().
   */
  def 'test blocking on json response body'() {
    setup:
    def request = request(
      BODY_JSON, 'POST',
      RequestBody.create(MediaType.get('application/json'), '{"a": "x"}'))
      .header(IG_BLOCK_RESPONSE_HEADER, 'body')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
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

  /**
   * SessionHandler creates a session and calls RoutingContextImpl.setSession(), reaching
   * RoutingContextSessionAdvice, whose requestSession callback blocks only for BLOCK_SESSION_MARKER.
   */
  def 'test blocking on session'() {
    setup:
    installBlockingSessionCallback()
    def request = request(SESSION_ID, 'GET', null)
      .header(IG_TEST_HEADER, BLOCK_SESSION_MARKER)
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 413
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)

    def spans = TEST_WRITER.flatten()
    spans.find {
      it.parentId == 0 && it.tags['http.status_code'] == 413 && it.tags['appsec.blocked'] == 'true'
    } != null
    spans.find {
      it.error && it.tags['error.type'] == BlockingException.name && it.tags['error.message'] == 'Blocked request (for session)'
    } != null

    cleanup:
    restoreSessionCallback()
  }

  static final String BLOCK_SESSION_MARKER = 'block-session'

  /**
   * The shared HttpServerTest requestSession callback never blocks, and registering a second
   * callback for the same event throws, so this spec's (per-spec) gateway slot is cleared and
   * replaced with a callback that blocks only when the IG test header carries BLOCK_SESSION_MARKER.
   */
  static void installBlockingSessionCallback() {
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(EVENTS.requestSession())
    ss.registerCallback(EVENTS.requestSession(), ({ RequestContext ctx, String sessionId ->
      def context = ctx.getData(RequestContextSlot.APPSEC)
      sessionId != null && context.matchingHeaderValue == BLOCK_SESSION_MARKER
        ? new RbaFlow(new Flow.Action.RequestBlockingAction(413, BlockingContentType.JSON))
        : Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, String, Flow<Void>>))
  }

  /** Restores the shared HttpServerTest requestSession callback replaced by installBlockingSessionCallback(). */
  void restoreSessionCallback() {
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(EVENTS.requestSession())
    ss.registerCallback(EVENTS.requestSession(), ({ RequestContext rqCtxt, String sessionId ->
      def context = rqCtxt.getData(RequestContextSlot.APPSEC)
      if (context != null && sessionId != null) {
        context.extraSpanName = 'appsec-span'
        context.tags.put(IG_SESSION_ID_TAG, sessionId)
      }
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, String, Flow<Void>>))
  }

  /**
   * Uploads a file part to BODY_MULTIPART, whose handler calls ctx.fileUploads(), reaching
   * RoutingContextFilenamesAdvice, whose requestFilesFilenames callback blocks only for BLOCKED_FILENAME.
   * The BlockingException message pins the filenames branch of FileUploadHelper.commitBlockingResponse.
   */
  def 'test blocking on multipart file upload filename'() {
    setup:
    installBlockingFilenamesCallback()
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('file', BLOCKED_FILENAME, RequestBody.create(MediaType.parse('application/octet-stream'), 'file content'))
      .build()
    def request = request(BODY_MULTIPART, 'POST', body).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 403
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)

    def spans = TEST_WRITER.flatten()
    spans.find {
      it.parentId == 0 && it.tags['http.status_code'] == 403 && it.tags['appsec.blocked'] == 'true'
    } != null
    spans.find {
      it.error && it.tags['error.type'] == BlockingException.name && it.tags['error.message'] == 'Blocked request (multipart file upload)'
    } != null

    cleanup:
    restoreFilenamesCallback()
  }

  static final String BLOCKED_FILENAME = 'block-me.php'

  /**
   * The shared HttpServerTest requestFilesFilenames callback never blocks, and registering a second
   * callback for the same event throws, so this spec's (per-spec) gateway slot is cleared and
   * replaced with a callback that blocks only when BLOCKED_FILENAME is uploaded.
   */
  static void installBlockingFilenamesCallback() {
    def ss = get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(EVENTS.requestFilesFilenames())
    ss.registerCallback(EVENTS.requestFilesFilenames(), ({ RequestContext ctx, List<String> filenames ->
      filenames.contains(BLOCKED_FILENAME)
        ? new RbaFlow(new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON))
        : Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>))
  }

  /** Restores the shared HttpServerTest requestFilesFilenames callback replaced by installBlockingFilenamesCallback(). */
  void restoreFilenamesCallback() {
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
  }
}

class VertxHttpServerWorkerAppSecForkedTest extends VertxHttpServerAppSecForkedTest {
  @Override
  HttpServer server() {
    return new VertxServer(verticle(), routerBasePath(), true)
  }
}

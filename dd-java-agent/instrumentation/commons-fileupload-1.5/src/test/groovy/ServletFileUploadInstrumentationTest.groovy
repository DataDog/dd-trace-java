import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.appsec.AppSecContext
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.smoketest.MockHttpServletRequest
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemIterator
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.fileupload.servlet.ServletFileUpload

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class ServletFileUploadInstrumentationTest extends InstrumentationSpecification {

  private Object iastCtx

  private final appSecSubscriptionService = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void setup() {
    iastCtx = Stub(IastContext)
  }

  @Override
  void cleanup() {
    InstrumentationBridge.clearIastModules()
    appSecSubscriptionService.reset()
  }

  void 'test commons fileupload ServletFileUpload parseRequest'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final fileItemFactory = new DiskFileItemFactory()
    final servletFileUpload = new ServletFileUpload(fileItemFactory)
    final contentType = "multipart/form-data"
    final inputStream = "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
      "Content-Type: text/plain\r\n" +
      "\r\n" +
      "This is a test file.\r\n"
    final characterEncoding = "UTF-8"
    final request = new MockHttpServletRequest(contentType, inputStream, characterEncoding)

    when:
    runUnderIastTrace { servletFileUpload.parseRequest(request) }

    then:
    1 * module.taintObject(iastCtx, _ as FileItem, SourceTypes.REQUEST_MULTIPART_PARAMETER)
  }

  void 'test commons fileupload ServletFileUpload parseParameterMap'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final fileItemFactory = new DiskFileItemFactory()
    final servletFileUpload = new ServletFileUpload(fileItemFactory)
    final contentType = "multipart/form-data"
    final inputStream = "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
      "Content-Type: text/plain\r\n" +
      "\r\n" +
      "This is a test file.\r\n"
    final characterEncoding = "UTF-8"
    final request = new MockHttpServletRequest(contentType, inputStream, characterEncoding)

    when:
    runUnderIastTrace { servletFileUpload.parseParameterMap(request) }

    then:
    1 * module.taintObject(iastCtx, _ as FileItem, SourceTypes.REQUEST_MULTIPART_PARAMETER)
  }

  void 'test commons fileupload ServletFileUpload getItemIterator'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final fileItemFactory = new DiskFileItemFactory()
    final servletFileUpload = new ServletFileUpload(fileItemFactory)
    final contentType = "multipart/form-data"
    final inputStream = "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
      "Content-Type: text/plain\r\n" +
      "\r\n" +
      "This is a test file.\r\n"
    final characterEncoding = "UTF-8"
    final request = new MockHttpServletRequest(contentType, inputStream, characterEncoding)

    when:
    runUnderIastTrace { servletFileUpload.getItemIterator(request) }

    then:
    1 * module.taintObject(iastCtx, _ as FileItemIterator, SourceTypes.REQUEST_MULTIPART_PARAMETER)
  }

  void 'test appsec commits the filenames blocking response with the request context'() {
    given:
    final appSecCtx = Stub(AppSecContext)
    final brf = Mock(BlockResponseFunction)
    appSecSubscriptionService.registerCallback(EVENTS.requestFilesFilenames(), { RequestContext reqCtx, List<String> filenames ->
      blockingFlow()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>)
    final servletFileUpload = new ServletFileUpload(new DiskFileItemFactory())

    when:
    runUnderAppSecTrace(appSecCtx, brf) { servletFileUpload.parseRequest(multipartRequest()) }

    then:
    thrown(BlockingException)
    1 * brf.tryCommitBlockingResponse(_ as RequestContext, _ as Flow.Action.RequestBlockingAction) >> true
  }

  void 'test appsec commits the file content blocking response with the request context'() {
    given:
    final appSecCtx = Stub(AppSecContext)
    final brf = Mock(BlockResponseFunction)
    appSecSubscriptionService.registerCallback(EVENTS.requestFilesContent(), { RequestContext reqCtx, List<String> contents ->
      blockingFlow()
    } as BiFunction<RequestContext, List<String>, Flow<Void>>)
    final servletFileUpload = new ServletFileUpload(new DiskFileItemFactory())

    when:
    runUnderAppSecTrace(appSecCtx, brf) { servletFileUpload.parseRequest(multipartRequest()) }

    then:
    thrown(BlockingException)
    1 * brf.tryCommitBlockingResponse(_ as RequestContext, _ as Flow.Action.RequestBlockingAction) >> true
  }

  private static MockHttpServletRequest multipartRequest() {
    final body = "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
      "Content-Type: text/plain\r\n" +
      "\r\n" +
      "This is a test file.\r\n"
    new MockHttpServletRequest('multipart/form-data', body, 'UTF-8')
  }

  private static Flow<Void> blockingFlow() {
    new Flow<Void>() {
        @Override
        Flow.Action getAction() {
          new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON)
        }

        @Override
        Void getResult() {
          null
        }
      }
  }

  protected <E> E runUnderAppSecTrace(Object appSecCtx, BlockResponseFunction brf, Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataAppSec(appSecCtx)
    final span = TEST_TRACER.startSpan("test", "test-appsec-span", ddctx)
    span.requestContext.blockResponseFunction = brf
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }

  protected <E> E runUnderIastTrace(Closure<E> cl) {
    final ddctx = new TagContext().withRequestContextDataIast(iastCtx)
    final span = TEST_TRACER.startSpan("test", "test-iast-span", ddctx)
    try {
      return AgentTracer.activateSpan(span).withCloseable(cl)
    } finally {
      span.finish()
    }
  }
}

import datadog.trace.agent.test.InstrumentationSpecification
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

class ServletFileUploadInstrumentationTest extends InstrumentationSpecification {

  private Object iastCtx

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

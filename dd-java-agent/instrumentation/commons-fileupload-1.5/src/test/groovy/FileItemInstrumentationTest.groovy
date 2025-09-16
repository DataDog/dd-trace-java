import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.smoketest.MockFileItem

class FileItemInstrumentationTest extends InstrumentationSpecification {

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

  void 'test commons fileupload FileItem getInputStream'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final inputStream = new ByteArrayInputStream('inputStream'.getBytes())
    final fileItem = new MockFileItem('fileItemName', inputStream)

    when:
    runUnderIastTrace { fileItem.getInputStream() }

    then:
    1 * module.taintObjectIfTainted(iastCtx, inputStream, fileItem)
    0 * _
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

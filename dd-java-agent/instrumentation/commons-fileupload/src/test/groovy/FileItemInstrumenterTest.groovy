import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.taint.TaintedObjects
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.smoketest.MockFileItem

class FileItemInstrumenterTest extends AgentTestRunner {

  private Object iastCtx
  private Object to

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @Override
  void setup() {
    to = Stub(TaintedObjects)
    iastCtx = Stub(IastContext) {
      getTaintedObjects() >> to
    }
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
    1 * module.taintObjectIfTainted(to, inputStream, fileItem)
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

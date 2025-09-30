import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.smoketest.MockFileItemIterator
import foo.bar.smoketest.MockFileItemStream

class FileItemIteratorInstrumentationTest extends InstrumentationSpecification {

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

  void 'test commons fileupload FileItemIterator next'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final fileItemStreams = new MockFileItemStream('fileItemStream1', null)
    final fileItemIterator = new MockFileItemIterator(fileItemStreams)

    when:
    runUnderIastTrace { fileItemIterator.next() }

    then:
    1 * module.taintObjectIfTainted(iastCtx, fileItemStreams, fileItemIterator)
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

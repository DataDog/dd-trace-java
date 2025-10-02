import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import foo.bar.smoketest.MockPart

class MultipartInstrumentationForkedTest extends InstrumentationSpecification {

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

  void 'test getName'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    runUnderIastTrace { part.getName() }

    then:
    1 * module.taintString(iastCtx, 'partName', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'Content-Disposition')
    0 * _
  }

  void 'test getHeader'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    runUnderIastTrace { part.getHeader('headerName') }

    then:
    1 * module.taintString(iastCtx, 'headerValue', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'headerName')
    0 * _
  }

  void 'test getHeaders'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    runUnderIastTrace { part.getHeaders('headerName') }

    then:
    1 * module.taintString(iastCtx, 'headerValue', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'headerName')
    0 * _
  }

  void 'test getHeaderNames'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final part = new MockPart('partName', 'headerName', 'headerValue')

    when:
    runUnderIastTrace { part.getHeaderNames() }

    then:
    1 * module.taintString(iastCtx, 'headerName', SourceTypes.REQUEST_MULTIPART_PARAMETER)
    0 * _
  }

  void 'test getInputStream'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final inputStream = new ByteArrayInputStream('inputStream'.getBytes())
    final part = new MockPart('partName', inputStream)

    when:
    runUnderIastTrace { part.getInputStream() }

    then:
    1 * module.taintObject(iastCtx, inputStream, SourceTypes.REQUEST_MULTIPART_PARAMETER)
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

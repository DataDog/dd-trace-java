import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext


class MultipartInstrumentationTest extends InstrumentationSpecification {

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

  void 'test commons fileupload ParameterParser.parse'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final content = "Content-Disposition: form-data; name=\"file\"; filename=\"=?ISO-8859-1?B?SWYgeW91IGNhbiByZWFkIHRoaXMgeW8=?= =?ISO-8859-2?B?dSB1bmRlcnN0YW5kIHRoZSBleGFtcGxlLg==?=\"\r\n"
    final parser = clazz.newInstance()

    when:
    runUnderIastTrace {
      parser.parse(content, new char[]{
        ',', ';'
      })
    }

    then:
    1 * module.taintString(iastCtx, 'file', SourceTypes.REQUEST_MULTIPART_PARAMETER, 'name')
    1 * module.taintString(iastCtx, _, SourceTypes.REQUEST_MULTIPART_PARAMETER, 'filename')
    0 * _

    where:
    clazz | _
    org.apache.commons.fileupload.ParameterParser | _
    org.apache.tomcat.util.http.fileupload.ParameterParser | _
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

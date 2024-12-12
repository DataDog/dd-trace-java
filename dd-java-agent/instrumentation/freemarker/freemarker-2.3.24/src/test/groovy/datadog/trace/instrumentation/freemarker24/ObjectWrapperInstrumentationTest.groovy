package datadog.trace.instrumentation.freemarker24

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.taint.TaintedObjects
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import freemarker.template.DefaultObjectWrapper

class ObjectWrapperInstrumentationTest extends  AgentTestRunner {

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

  void 'test freemarker ObjectWrapper wrap'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)
    final objectWrapper = new DefaultObjectWrapper()
    final String wrapped = "test"

    when:
    runUnderIastTrace { objectWrapper.wrap(wrapped) }

    then:
    1 * module.taintObjectIfTainted(to, _, wrapped)
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

package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakRandomnessModule
import foo.bar.TestMathSuite

class MathCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test random'() {
    setup:
    final module = Mock(WeakRandomnessModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestMathSuite.random()

    then:
    1 * module.onWeakRandom(Math)
  }
}


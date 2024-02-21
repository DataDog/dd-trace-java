package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestClassSuite
import foo.bar.TestLookupSuite

class LookupCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test onLookupMethod'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final test = 'test'

    when:
    TestLookupSuite.&"$method".call(test)

    then:
    1 * module.onLookupMethod(test)

    where:
    method             | _
    'bind'             | _
    'findGetter'       | _
    'findSetter'       | _
    'findSpecial'      | _
    'findStatic'       | _
    'findStaticGetter' | _
    'findStaticSetter' | _
    'findVirtual'      | _
  }
}


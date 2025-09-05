package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestClassLoaderSuite

class ClassLoaderCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test onClassName'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestClassLoaderSuite.loadClass('java.lang.String')

    then:
    1 * module.onClassName('java.lang.String')
  }
}


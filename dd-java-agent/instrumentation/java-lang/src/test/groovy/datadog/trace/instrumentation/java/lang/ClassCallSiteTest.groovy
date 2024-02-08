package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestClassSuite

class ClassCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test reflection'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestClassSuite.&"$method".call(args.toArray())

    then:
    1 * module.onReflection(args[0])

    where:
    method | args
    'forName' | ['java.lang.String']
    'forName' | ['java.lang.String', true, ClassLoader.getSystemClassLoader()]
    'getMethod' | ['contains', String, CharSequence]
    'getDeclaredMethod' | ['contains', String, CharSequence]
  }
}


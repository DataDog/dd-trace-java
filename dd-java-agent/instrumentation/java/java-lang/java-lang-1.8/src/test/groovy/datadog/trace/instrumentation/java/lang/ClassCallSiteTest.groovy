package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestClassSuite

class ClassCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test onClassName'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestClassSuite.&"$method".call(args.toArray())

    then:
    1 * module.onClassName(args[0])

    where:
    method | args
    'forName' | ['java.lang.String']
    'forName' | ['java.lang.String', true, ClassLoader.getSystemClassLoader()]
  }

  void 'test onMethodName'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestClassSuite.&"$method".call(args.toArray())

    then:
    1 * module.onMethodName(args[0], args[1], args[2])

    where:

    where:
    method | args
    'getMethod' | [String, 'contains', CharSequence]
    'getDeclaredMethod' | [String, 'contains', CharSequence]
  }

  void 'test onFieldName'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestClassSuite.&"$method".call(args.toArray())

    then:
    1 * module.onFieldName(args[0], args[1])

    where:
    method | args
    'getDeclaredField' | [String, 'hash']
    'getField' | [String, 'CASE_INSENSITIVE_ORDER']
  }
}


package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestLookupSuite

import java.lang.invoke.MethodType

class LookupCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test beforeFindSetter'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final clazz = String
    final methodName = 'field'
    final fieldsType = String

    when:
    TestLookupSuite.&"$suiteMethod".call(clazz, methodName, fieldsType)

    then:
    1 * module.onFieldName(clazz, methodName)

    where:
    suiteMethod        | _
    'findSetter'       | _
    'findStaticSetter' | _
    'findGetter'       | _
    'findStaticGetter' | _
  }

  void 'test beforeMethod'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final clazz = String
    final methodName = 'methodName'
    final methodType = MethodType.methodType(String)

    when:
    TestLookupSuite.&"$suiteMethod".call(clazz, methodName, methodType)

    then:
    1 * module.onMethodName(clazz, methodName, methodType.parameterArray())

    where:
    suiteMethod        | _
    'findSpecial'      | _
    'findStatic'       | _
    'findVirtual'      | _
  }

  void 'test bind'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final obj = 'test'
    final methodName = 'methodName'
    final methodType = MethodType.methodType(String)

    when:
    TestLookupSuite.bind(obj, methodName, methodType)

    then:
    1 * module.onMethodName(obj.getClass(), methodName, methodType.parameterArray())
  }
}


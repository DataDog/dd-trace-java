package datadog.trace.instrumentation.java.lang.invoke

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ReflectionInjectionModule
import foo.bar.TestLookup9Suite
import spock.lang.Requires

@Requires({
  jvm.java9Compatible
})
class Lookup9CallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test beforeFindVar'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final clazz = String
    final fieldName = 'field'
    final fieldType = String

    when:
    TestLookup9Suite.&"$suiteMethod".call(clazz, fieldName, fieldType)

    then:
    1 * module.onFieldName(clazz, fieldName)

    where:
    suiteMethod           | _
    'findStaticVarHandle' | _
    'findVarHandle'       | _
  }

  void 'test findClass'() {
    setup:
    final module = Mock(ReflectionInjectionModule)
    InstrumentationBridge.registerIastModule(module)
    final className = 'java.lang.String'

    when:
    TestLookup9Suite.findClass(className)

    then:
    1 * module.onClassName(className)
  }
}


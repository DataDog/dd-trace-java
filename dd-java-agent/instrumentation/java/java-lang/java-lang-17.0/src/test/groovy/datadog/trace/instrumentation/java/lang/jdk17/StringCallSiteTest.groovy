package datadog.trace.instrumentation.java.lang.jdk17

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringJDK17Suite
import spock.lang.Requires

@Requires({
  jvm.java17Compatible
})
class StringCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string indent call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringJDK17Suite.stringIndent(input, indentation)

    then:
    result == output
    1 * iastModule.onIndent(input, indentation, output)

    where:
    input                   | indentation | output
    'Hello\nThis is a line' | 3           | '   Hello\n   This is a line\n'
  }
}

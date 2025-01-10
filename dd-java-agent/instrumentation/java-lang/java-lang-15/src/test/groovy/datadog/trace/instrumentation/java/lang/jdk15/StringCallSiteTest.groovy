package datadog.trace.instrumentation.java.lang.jdk15

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringJDK15Suite
import spock.lang.Requires

@Requires({
  jvm.java15Compatible
})
class StringCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string translate escapes call site'() {
    setup:
    final iastModule = Mock(StringModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringJDK15Suite.stringTranslateEscapes(input)

    then:
    result == output
    1 * iastModule.onStringTranslateEscapes(input, output)

    where:
    input                   | output
    'Hello\tThis is a line' | 'Hello\n   This is a line\n'
  }
}

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.StringModule
import foo.bar.TestStringJDK11Suite
import spock.lang.Requires

@Requires({
  jvm.java11Compatible
})
class StringCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string join call site'() {
    setup:
    final iastModule = Mock(StringModule)
    final self = 'abc'
    final count = 3
    final expected = 'abcabcabc'
    InstrumentationBridge.registerIastModule(iastModule)


    when:
    final result = TestStringJDK11Suite.stringRepeat(self, count)

    then:
    result == expected
    1 * iastModule.onStringRepeat(self, count, expected)
    0 * _
  }
}

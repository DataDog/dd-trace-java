import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestHtmlUtilsSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class HtmlUtilsCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test htmlEscape'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestHtmlUtilsSuite.&htmlEscape.call(args)

    then:
    result == expected
    1 * module.taintIfInputIsTaintedWithMarks(_ as String, args[0], VulnerabilityMarks.XSS_MARK)
    0 * _

    where:
    args                                | expected
    ['Ø-This is a quote']               | '&Oslash;-This is a quote'
    ['Ø-This is a quote', 'ISO-8859-1'] | '&Oslash;-This is a quote'
  }
}

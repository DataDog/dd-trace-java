package datadog.trace.instrumentation.owasp.esapi

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestEncoderSuite
import org.owasp.esapi.Encoder

class EncoderCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    final testSuite = new TestEncoderSuite(Mock(Encoder))
    InstrumentationBridge.registerIastModule(module)

    when:
    testSuite.&"$method".call(args)

    then:
    1 * module.taintIfInputIsTaintedWithMarks(_, args[0], VulnerabilityMarks.XSS_MARK)
    0 * module._

    where:
    method          | args
    'encodeForHTML' | ['Ø-This is a quote']
  }
}

package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.sink.WeakCipherModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestSuite
import datadog.trace.api.Config

class DisabledWeakCipherForkedTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
    injectSysConfig("iast.weak-cipher.enabled", "false")
  }

  def "test weak cipher instrumentation"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance("DES")

    then:
    false == Config.get().isEnabled(true, "iast.weak-cipher.enabled", "")
    0 * module.onCipherAlgorithm(_)
  }
}

package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.sink.WeakHashModule
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.Config

import java.security.MessageDigest

class DisabledWeakHashForkedTest extends AgentTestRunner {
  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
    injectSysConfig("iast.weak-hash.enabled", "false")
  }

  def "test weak hash instrumentation"() {
    setup:
    WeakHashModule module = Mock(WeakHashModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    MessageDigest.getInstance("MD2")

    then:
    false == Config.get().isEnabled(true, "iast.weak-hash.enabled", "")
    0 * module.onHashingAlgorithm("MD2")
  }
}

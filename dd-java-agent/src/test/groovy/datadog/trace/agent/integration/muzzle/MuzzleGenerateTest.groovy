package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

class MuzzleGenerateTest extends Specification {

  def "muzzle references generated for all instrumentation"() {
    setup:
    List<Class> missingMatchers = []
    ClassLoader agentClassLoader = IntegrationTestUtils.getAgentClassLoader()
    Class<?> instrumenterClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.Instrumenter')
    Class<?> instrumenterModuleClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.InstrumenterModule')
    for (Object instrumenter : ServiceLoader.load(instrumenterClass, agentClassLoader)) {
      if (!instrumenterModuleClass.isInstance(instrumenter)) {
        // muzzle only applies to instrumenter modules
        continue
      }
      if (instrumenter.instrumentationMuzzle == null) {
        missingMatchers.add(instrumenter.class)
      }
    }
    expect:
    missingMatchers == []
  }
}

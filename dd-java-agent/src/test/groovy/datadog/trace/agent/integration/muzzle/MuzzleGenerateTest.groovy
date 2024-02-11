package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

class MuzzleGenerateTest extends Specification {

  def "muzzle references generated for all instrumentation"() {
    setup:
    List<Class> missingMatchers = []
    ClassLoader agentClassLoader = IntegrationTestUtils.agentClassLoader
    Class<?> instrumenterModuleClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.InstrumenterModule')
    for (Object module : ServiceLoader.load(instrumenterModuleClass, agentClassLoader)) {
      if (module.instrumentationMuzzle == null) {
        missingMatchers.add(module.class)
      }
    }
    expect:
    missingMatchers == []
  }
}

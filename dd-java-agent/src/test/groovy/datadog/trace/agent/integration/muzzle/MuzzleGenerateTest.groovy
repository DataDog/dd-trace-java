package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

class MuzzleGenerateTest extends Specification {

  def "muzzle references generated for all instrumentation"() {
    setup:
    List<Class> missingMatchers = []
    ClassLoader agentClassLoader = IntegrationTestUtils.agentClassLoader
    Class<?> moduleClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.InstrumenterModule')
    for (Object module : ServiceLoader.load(moduleClass, agentClassLoader)) {
      if (module.instrumentationMuzzle == null) {
        missingMatchers.add(module.class)
      }
    }
    expect:
    missingMatchers == []
  }
}

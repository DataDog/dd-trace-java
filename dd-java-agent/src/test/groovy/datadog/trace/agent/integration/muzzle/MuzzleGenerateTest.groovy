package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

class MuzzleGenerateTest extends Specification {

  def "muzzle references generated for all instrumentation"() {
    setup:
    List<Class> missingMatchers = []
    ClassLoader agentClassLoader = IntegrationTestUtils.getAgentClassLoader()
    Class<?> instrumenterClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.Instrumenter')
    Class<?> instrumenterGroupClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.InstrumenterGroup')
    for (Object instrumenter : ServiceLoader.load(instrumenterClass, agentClassLoader)) {
      if (!instrumenterGroupClass.isInstance(instrumenter)) {
        // muzzle only applies to instrumenter groups
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

package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

class MuzzleGenerateTest extends Specification {

  def "muzzle references generated for all instrumentation"() {
    setup:
    List<Class> missingMatchers = []
    ClassLoader agentClassLoader = IntegrationTestUtils.getAgentClassLoader()
    Class<?> instrumenterClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.Instrumenter')
    Class<?> instrumenterDefaultClass = agentClassLoader.loadClass('datadog.trace.agent.tooling.Instrumenter$Default')
    for (Object instrumenter : ServiceLoader.load(instrumenterClass, agentClassLoader)) {
      if (instrumenter.class.name.endsWith("TraceConfigInstrumentation")) {
        // TraceConfigInstrumentation doesn't do muzzle checks
        // check on TracerClassInstrumentation instead
        instrumenter = agentClassLoader.loadClass(instrumenter.class.name + '$TracerClassInstrumentation').newInstance()
      }
      if (!instrumenterDefaultClass.isInstance(instrumenter)) {
        // muzzle only applies to default instrumenters
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

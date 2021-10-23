package datadog.trace.agent.integration.muzzle

import datadog.trace.agent.test.IntegrationTestUtils
import spock.lang.Specification

import java.lang.reflect.Method

class MuzzleBytecodeTransformTest extends Specification {

  def "muzzle fields added to all instrumentation"() {
    setup:
    List<Class> unMuzzledClasses = []
    List<Class> missingMatchers = []
    for (Object instrumenter : ServiceLoader.load(IntegrationTestUtils.getAgentClassLoader().loadClass("datadog.trace.agent.tooling.Instrumenter"), IntegrationTestUtils.getAgentClassLoader())) {
      if (instrumenter.getClass().getName().endsWith("TraceConfigInstrumentation")) {
        // TraceConfigInstrumentation doesn't do muzzle checks
        // check on TracerClassInstrumentation instead
        instrumenter = IntegrationTestUtils.getAgentClassLoader().loadClass(instrumenter.getClass().getName() + '$TracerClassInstrumentation').newInstance()
      }
      if (!IntegrationTestUtils.getAgentClassLoader().loadClass('datadog.trace.agent.tooling.Instrumenter$Default').isAssignableFrom(instrumenter.getClass())) {
        // muzzle only applies to default instrumenters
        continue
      }
      Method m
      try {
        m = instrumenter.getClass().getDeclaredMethod("getInstrumentationMuzzle")
        m.setAccessible(true)
        if (m.invoke(instrumenter) == null) {
          missingMatchers.add(instrumenter.getClass())
        }
      } catch (NoSuchFieldException | NoSuchMethodException e) {
        unMuzzledClasses.add(instrumenter.getClass())
      } finally {
        if (null != m) {
          m.setAccessible(false)
        }
      }
    }
    expect:
    unMuzzledClasses == []
    missingMatchers == []
  }
}

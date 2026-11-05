package datadog.trace.agent.integration.muzzle;

import static datadog.trace.agent.test.IntegrationTestUtils.getAgentClassLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class MuzzleGenerateTest {

  @Test
  void muzzleReferencesGeneratedForAllInstrumentation() throws Exception {
    // setup
    List<Class<?>> missingMatchers = new ArrayList<>();
    ClassLoader agentClassLoader = getAgentClassLoader();
    Class<?> moduleClass =
        agentClassLoader.loadClass("datadog.trace.agent.tooling.InstrumenterModule");
    Method getInstrumentationMuzzle = moduleClass.getMethod("getInstrumentationMuzzle");
    for (Object module : ServiceLoader.load(moduleClass, agentClassLoader)) {
      if (getInstrumentationMuzzle.invoke(module) == null) {
        missingMatchers.add(module.getClass());
      }
    }
    // expect
    assertEquals(Collections.<Class<?>>emptyList(), missingMatchers);
  }
}

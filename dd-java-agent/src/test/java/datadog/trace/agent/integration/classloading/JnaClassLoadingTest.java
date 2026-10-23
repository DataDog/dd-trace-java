package datadog.trace.agent.integration.classloading;

import static datadog.trace.agent.test.IntegrationTestUtils.getAgentClassLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class JnaClassLoadingTest {
  @Test
  void allocatesAndClosesNativeMemoryFromAgentJar() throws Exception {
    ClassLoader agentClassLoader = getAgentClassLoader();
    Class<?> memoryClass = agentClassLoader.loadClass("com.sun.jna.Memory");
    assertSame(agentClassLoader, memoryClass.getClassLoader());

    // Exercise the packaged jnidispatch and cleaner, including the safe logging rewrite.
    try (AutoCloseable memory =
        (AutoCloseable) memoryClass.getConstructor(long.class).newInstance(8L)) {
      memoryClass.getMethod("setLong", long.class, long.class).invoke(memory, 0L, 42L);
      assertEquals(42L, memoryClass.getMethod("getLong", long.class).invoke(memory, 0L));
    }
  }
}

package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentFeatureFlaggingLifecycleTest {

  @BeforeEach
  void reset() {
    FakeFeatureFlaggingSystem.stopCalls.set(0);
  }

  @Test
  void shutdownInvokesFeatureFlaggingSystemStopThroughAgentClassLoader() {
    final ClassLoader classLoader =
        new ClassLoader(null) {
          @Override
          public Class<?> loadClass(final String name) throws ClassNotFoundException {
            if ("com.datadog.featureflag.FeatureFlaggingSystem".equals(name)) {
              return FakeFeatureFlaggingSystem.class;
            }
            return super.loadClass(name);
          }
        };

    Agent.shutdownFeatureFlagging(classLoader);

    assertEquals(1, FakeFeatureFlaggingSystem.stopCalls.get());
  }

  @Test
  void shutdownIsNoopBeforeAgentClassLoaderExists() {
    Agent.shutdownFeatureFlagging(null);

    assertEquals(0, FakeFeatureFlaggingSystem.stopCalls.get());
  }

  public static final class FakeFeatureFlaggingSystem {
    private static final AtomicInteger stopCalls = new AtomicInteger();

    public static void stop() {
      stopCalls.incrementAndGet();
    }
  }
}

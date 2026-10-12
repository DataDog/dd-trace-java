package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentFeatureFlaggingLifecycleTest {

  @BeforeEach
  void reset() {
    FakeFeatureFlaggingSystem.publishSpanEnrichmentConfigurationCalls.set(0);
    FakeFeatureFlaggingSystem.stopCalls.set(0);
  }

  @Test
  void publishesSpanEnrichmentConfigurationThroughAgentClassLoader() {
    Agent.publishFeatureFlaggingSpanEnrichmentConfiguration(featureFlaggingClassLoader());

    assertEquals(1, FakeFeatureFlaggingSystem.publishSpanEnrichmentConfigurationCalls.get());
  }

  @Test
  void publishingSpanEnrichmentConfigurationIsNoopBeforeAgentClassLoaderExists() {
    Agent.publishFeatureFlaggingSpanEnrichmentConfiguration(null);

    assertEquals(0, FakeFeatureFlaggingSystem.publishSpanEnrichmentConfigurationCalls.get());
  }

  @Test
  void shutdownInvokesFeatureFlaggingSystemStopThroughAgentClassLoader() {
    Agent.shutdownFeatureFlagging(featureFlaggingClassLoader());

    assertEquals(1, FakeFeatureFlaggingSystem.stopCalls.get());
  }

  @Test
  void shutdownIsNoopBeforeAgentClassLoaderExists() {
    Agent.shutdownFeatureFlagging(null);

    assertEquals(0, FakeFeatureFlaggingSystem.stopCalls.get());
  }

  private static ClassLoader featureFlaggingClassLoader() {
    return new ClassLoader(null) {
      @Override
      public Class<?> loadClass(final String name) throws ClassNotFoundException {
        if ("com.datadog.featureflag.FeatureFlaggingSystem".equals(name)) {
          return FakeFeatureFlaggingSystem.class;
        }
        return super.loadClass(name);
      }
    };
  }

  public static final class FakeFeatureFlaggingSystem {
    private static final AtomicInteger publishSpanEnrichmentConfigurationCalls =
        new AtomicInteger();
    private static final AtomicInteger stopCalls = new AtomicInteger();

    public static void publishSpanEnrichmentConfiguration() {
      publishSpanEnrichmentConfigurationCalls.incrementAndGet();
    }

    public static void stop() {
      stopCalls.incrementAndGet();
    }
  }
}

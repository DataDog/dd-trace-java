package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SpanEnrichmentGateTest {

  @AfterEach
  void resetGateway() {
    FeatureFlaggingGateway.setSpanEnrichmentEnabled(false);
  }

  @Test
  void readsResolvedGatewayState() {
    assertFalse(SpanEnrichmentGate.isEnabled());

    FeatureFlaggingGateway.setSpanEnrichmentEnabled(true);

    assertTrue(SpanEnrichmentGate.isEnabled());
  }

  @Test
  void defaultsToDisabledWithoutBootstrapSupport() throws Exception {
    final URL classes =
        SpanEnrichmentGate.class.getProtectionDomain().getCodeSource().getLocation();
    try (URLClassLoader isolatedLoader =
        new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
      final Class<?> isolatedGate =
          Class.forName(SpanEnrichmentGate.class.getName(), true, isolatedLoader);
      final Method isEnabled = isolatedGate.getDeclaredMethod("isEnabled");
      isEnabled.setAccessible(true);

      assertFalse((boolean) isEnabled.invoke(null));
    }
  }
}

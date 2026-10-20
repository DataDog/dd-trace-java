package datadog.trace.instrumentation.openfeature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LegacyProviderInstrumentationTest extends AbstractInstrumentationTest {
  @Test
  @Timeout(10)
  void initializesTheReleasedProviderWithoutLoadingItsLegacyEvaluator() throws Exception {
    final URL jar = getClass().getResource("/legacy-sdk/dd-openfeature-1.67.0.jar");
    assertNotNull(jar);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try (final LegacySdkLoader loader = new LegacySdkLoader(jar, getClass().getClassLoader())) {
      try {
        final FeatureProvider provider =
            (FeatureProvider)
                loader
                    .loadClass("datadog.trace.api.openfeature.Provider")
                    .getConstructor()
                    .newInstance();
        assertDoesNotThrow(() -> api.setProviderAndWait(provider));
        final Client client = api.getClient();
        assertEquals(ProviderState.READY, client.getProviderState());
        final Value object = new Value(new ImmutableStructure(Map.of("fallback", new Value(true))));
        final List<FlagEvaluationDetails<?>> evaluations =
            List.of(
                client.getBooleanDetails("boolean", true),
                client.getStringDetails("string", "fallback"),
                client.getIntegerDetails("integer", -1),
                client.getDoubleDetails("double", -1.0),
                client.getObjectDetails("object", object));
        final List<?> defaults = List.of(true, "fallback", -1, -1.0, object);
        for (int i = 0; i < evaluations.size(); i++) {
          final FlagEvaluationDetails<?> evaluation = evaluations.get(i);
          assertEquals(defaults.get(i), evaluation.getValue());
          assertEquals(ErrorCode.PROVIDER_NOT_READY, evaluation.getErrorCode());
          assertEquals("ERROR", evaluation.getReason());
          assertNull(evaluation.getVariant());
        }
        assertFalse(loader.evaluatorRequested, "the removed bootstrap API must never be linked");
      } finally {
        assertDoesNotThrow(api::shutdown);
      }
    }
  }

  private static final class LegacySdkLoader extends URLClassLoader {
    private boolean evaluatorRequested;

    LegacySdkLoader(final URL jar, final ClassLoader parent) {
      super(new URL[] {jar}, parent);
    }

    @Override
    public URL getResource(final String name) {
      // Agent type matching must see the same SDK bytecode that this loader defines.
      return name.startsWith("datadog/trace/api/openfeature/")
          ? findResource(name)
          : super.getResource(name);
    }

    @Override
    protected synchronized Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (!name.startsWith("datadog.trace.api.openfeature.")) {
        return super.loadClass(name, resolve);
      }
      evaluatorRequested |= name.endsWith(".DDEvaluator");
      Class<?> type = findLoadedClass(name);
      if (type == null) {
        type = findClass(name);
      }
      if (resolve) {
        resolveClass(type);
      }
      return type;
    }
  }
}

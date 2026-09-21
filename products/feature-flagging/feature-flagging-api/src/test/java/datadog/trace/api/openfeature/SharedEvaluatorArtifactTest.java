package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.featureflag.core.FlagEvaluator;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class SharedEvaluatorArtifactTest {
  @Test
  void adapterConsumesOnlyEvaluatorClassesFromTheSharedLibrary() throws Exception {
    try (JarFile jar =
        new JarFile(
            Paths.get(
                    FlagEvaluator.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toFile())) {
      assertNotNull(jar.getEntry("com/datadog/featureflag/core/FlagEvaluator.class"));
      assertNotNull(jar.getEntry("com/datadog/featureflag/core/ConfigurationStore.class"));
      assertTrue(
          jar.stream()
              .filter(entry -> entry.getName().endsWith(".class"))
              .allMatch(entry -> entry.getName().startsWith("com/datadog/featureflag/core/")),
          "The evaluator artifact must not contain the full configuration or event runtime");
    }
  }

  @Test
  void adapterDoesNotPullTheFullRuntimeOntoItsClasspath() {
    ClassLoader loader = FlagEvaluator.class.getClassLoader();
    assertNull(loader.getResource("com/datadog/featureflag/UniversalFlagConfigParser.class"));
    assertNull(loader.getResource("com/datadog/featureflag/AgentlessConfigurationSource.class"));
    assertNull(loader.getResource("com/datadog/featureflag/ProviderRuntime.class"));
    assertNull(loader.getResource("com/datadog/featureflag/DirectEventPipelines.class"));
    assertNull(loader.getResource("okhttp3/OkHttpClient.class"));
  }
}

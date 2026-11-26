package datadog.trace.api;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_EXCLUDES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class ConfigCodeCoverageExcludesTest {

  @Test
  void defaultExcludesAreNotProvided() {
    Config config = Config.get();

    assertFalse(config.isCiVisibilityCodeCoverageExcludesProvided());
    assertArrayEquals(
        new String[] {"datadog/trace/", "org/apache/commons/", "org/mockito/"},
        config.getCiVisibilityCodeCoverageExcludedPackages());
  }

  @Test
  void userExcludesReplaceTheDefaults() {
    WithConfigExtension.injectSysConfig(CIVISIBILITY_CODE_COVERAGE_EXCLUDES, "datadog.trace.*");
    Config config = Config.get();

    assertTrue(config.isCiVisibilityCodeCoverageExcludesProvided());
    assertArrayEquals(
        new String[] {"datadog/trace/"}, config.getCiVisibilityCodeCoverageExcludedPackages());
  }

  @Test
  void excludesFromTheEnvironmentAreProvided() {
    WithConfigExtension.injectEnvConfig(
        "DD_CIVISIBILITY_CODE_COVERAGE_EXCLUDES", "datadog.trace.*:org.example.*", false);

    assertTrue(Config.get().isCiVisibilityCodeCoverageExcludesProvided());
    assertArrayEquals(
        new String[] {"datadog/trace/", "org/example/"},
        Config.get().getCiVisibilityCodeCoverageExcludedPackages());
  }
}

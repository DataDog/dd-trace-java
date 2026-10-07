package datadog.trace.api;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_INCLUDES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class ConfigCodeCoveragePackagesTest {

  @Test
  void anEmptyIncludeListMatchesNoPackage() {
    // A parent with no root packages propagates an empty value to its workers.
    WithConfigExtension.injectSysConfig(CIVISIBILITY_CODE_COVERAGE_INCLUDES, "");

    assertArrayEquals(new String[0], Config.get().getCiVisibilityCodeCoverageIncludedPackages());
  }

  @Test
  void includesBecomePackagePrefixes() {
    WithConfigExtension.injectSysConfig(
        CIVISIBILITY_CODE_COVERAGE_INCLUDES, "datadog.*:com.example:*");

    assertArrayEquals(
        new String[] {"datadog/", "com/example", ""},
        Config.get().getCiVisibilityCodeCoverageIncludedPackages());
  }
}

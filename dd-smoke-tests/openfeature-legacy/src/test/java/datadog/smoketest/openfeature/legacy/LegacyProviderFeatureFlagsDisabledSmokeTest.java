package datadog.smoketest.openfeature.legacy;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The agent keeps released providers safe even with Feature Flags disabled. */
class LegacyProviderFeatureFlagsDisabledSmokeTest extends AbstractLegacyProviderSmokeTest {
  @RegisterExtension
  static final SmokeCliApp APP =
      legacyProviderApp("openfeature-legacy-disabled")
          .env("DD_FEATURE_FLAGS_ENABLED", "false")
          .build();

  @Override
  SmokeCliApp app() {
    return APP;
  }
}

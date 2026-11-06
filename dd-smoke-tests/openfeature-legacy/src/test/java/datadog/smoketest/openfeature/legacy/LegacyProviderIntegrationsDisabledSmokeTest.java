package datadog.smoketest.openfeature.legacy;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class LegacyProviderIntegrationsDisabledSmokeTest extends AbstractLegacyProviderSmokeTest {
  @RegisterExtension
  static final SmokeCliApp APP =
      legacyProviderApp("openfeature-legacy-integrations-disabled")
          .env("DD_INTEGRATIONS_ENABLED", "false")
          .build();

  @Override
  SmokeCliApp app() {
    return APP;
  }
}

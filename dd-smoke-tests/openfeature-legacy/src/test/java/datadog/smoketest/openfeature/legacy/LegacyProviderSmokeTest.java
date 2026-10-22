package datadog.smoketest.openfeature.legacy;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class LegacyProviderSmokeTest extends AbstractLegacyProviderSmokeTest {
  @RegisterExtension
  static final SmokeCliApp APP =
      legacyProviderApp("openfeature-legacy")
          .env("DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED", "true")
          .build();

  @Override
  SmokeCliApp app() {
    return APP;
  }
}

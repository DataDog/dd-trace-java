package datadog.smoketest.springboot;

import static datadog.smoketest.springboot.OpenFeatureFixtures.agentlessApp;

import datadog.smoketest.SmokeServerApp;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The SDK without the Datadog Java agent: the SDK polls the flag configuration from the CDN.
 * Without an API key, events are not delivered.
 */
class OpenFeatureStandaloneSmokeTest extends AbstractOpenFeatureSmokeTest {
  @RegisterExtension
  static final SmokeServerApp APP =
      agentlessApp("openfeature-standalone")
          // Disables direct intake delivery, which would otherwise reach the real Datadog intake.
          .env("DD_API_KEY", "")
          .noAgent()
          .build();

  @Override
  SmokeServerApp app() {
    return APP;
  }

  @Override
  boolean deliversExposures() {
    return false;
  }
}

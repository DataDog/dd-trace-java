package datadog.smoketest.springboot;

import static datadog.smoketest.springboot.OpenFeatureFixtures.agentlessApp;

import datadog.smoketest.SmokeServerApp;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The SDK with the Datadog Java agent and the default agentless configuration source: the SDK polls
 * the flag configuration from the CDN and delivers exposures through the agent.
 */
class OpenFeatureAgentlessSmokeTest extends AbstractOpenFeatureSmokeTest {
  @RegisterExtension
  static final SmokeServerApp APP = agentlessApp("openfeature-agentless").build();

  @Override
  SmokeServerApp app() {
    return APP;
  }

  @Override
  boolean deliversExposures() {
    return true;
  }
}

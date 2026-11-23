package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Outside AWS Lambda the bootstrap Remote Configuration check stays on by default. */
@ExtendWith(WithConfigExtension.class)
class AgentRemoteConfigTest {

  @Test
  void remoteConfigIsOnByDefaultOutsideAwsLambda() {
    assertTrue(Agent.isRemoteConfigEnabled());
  }
}

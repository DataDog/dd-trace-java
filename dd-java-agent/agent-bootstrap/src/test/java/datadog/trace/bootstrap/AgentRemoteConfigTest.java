package datadog.trace.bootstrap;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

/**
 * The bootstrap Remote Configuration check decides whether AppSec starts and must agree with
 * Config: off by default in AWS Lambda. The Datadog Lambda wrapper sets the legacy {@code
 * DD_REMOTE_CONFIG_ENABLED} to false.
 */
@ExtendWith(WithConfigExtension.class)
class AgentRemoteConfigTest {

  @TableTest({
    "scenario                         | functionName  | enabled | legacyEnabled | expected",
    "outside Lambda                   |               |         |               | true    ",
    "empty function name              | ''            |         |               | true    ",
    "Lambda                           | 'my-function' |         |               | false   ",
    "Lambda, enabled                  | 'my-function' | 'true'  |               | true    ",
    "Lambda, enabled with legacy key  | 'my-function' |         | 'true'        | true    ",
    "Lambda, disabled with legacy key | 'my-function' |         | 'false'       | false   ",
    "Lambda, either key enables       | 'my-function' | 'true'  | 'false'       | true    "
  })
  void remoteConfigIsOffByDefaultInAwsLambda(
      String functionName, String enabled, String legacyEnabled, boolean expected) {
    if (functionName != null) {
      injectEnvConfig("AWS_LAMBDA_FUNCTION_NAME", functionName, false);
    }
    if (enabled != null) {
      injectEnvConfig("DD_REMOTE_CONFIGURATION_ENABLED", enabled, false);
    }
    if (legacyEnabled != null) {
      injectEnvConfig("DD_REMOTE_CONFIG_ENABLED", legacyEnabled, false);
    }

    assertEquals(expected, Agent.isRemoteConfigEnabled());
  }
}

package datadog.trace.bootstrap;

import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

/**
 * In AWS Lambda the bootstrap Remote Configuration check, which decides whether AppSec starts, is
 * off unless explicitly enabled, like Config. The Datadog Lambda wrapper sets the legacy {@code
 * DD_REMOTE_CONFIG_ENABLED} to false.
 *
 * <p>Forked because the feature defaults are read once, when {@link Agent} is initialized, so the
 * Lambda environment must be set before that happens in this JVM.
 */
@ExtendWith(WithConfigExtension.class)
@WithConfig(key = "AWS_LAMBDA_FUNCTION_NAME", value = "my-function", env = true, addPrefix = false)
class AgentRemoteConfigLambdaForkedTest {

  @TableTest({
    "scenario                 | enabled | legacyEnabled | expected",
    "default                  |         |               | false   ",
    "enabled                  | 'true'  |               | true    ",
    "enabled with legacy key  |         | 'true'        | true    ",
    "disabled with legacy key |         | 'false'       | false   ",
    "either key enables       | 'true'  | 'false'       | true    "
  })
  void remoteConfigIsOffByDefaultInAwsLambda(
      String enabled, String legacyEnabled, boolean expected) {
    if (enabled != null) {
      injectEnvConfig("DD_REMOTE_CONFIGURATION_ENABLED", enabled, false);
    }
    if (legacyEnabled != null) {
      injectEnvConfig("DD_REMOTE_CONFIG_ENABLED", legacyEnabled, false);
    }

    assertEquals(expected, Agent.isRemoteConfigEnabled());
  }
}

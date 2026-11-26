package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.MeterInstaller.LANG_INTERPRETER_STATSD_TAG;
import static datadog.trace.agent.tooling.MeterInstaller.LANG_INTERPRETER_VENDOR_STATSD_TAG;
import static datadog.trace.agent.tooling.MeterInstaller.LANG_STATSD_TAG;
import static datadog.trace.agent.tooling.MeterInstaller.LANG_VERSION_STATSD_TAG;
import static datadog.trace.agent.tooling.MeterInstaller.TRACER_VERSION_STATSD_TAG;
import static datadog.trace.agent.tooling.MeterInstaller.generateConstantTags;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MeterInstallerTest extends DDJavaSpecification {

  @TableTest({
    "scenario              | service     | env     | version",
    "no tags               |             |         |        ",
    "service               | testService |         |        ",
    "service and env       | testService | staging |        ",
    "service and version   | testService |         | 1      ",
    "service, env, version | testService | staging | 1      ",
    "env                   |             | staging |        ",
    "env and version       |             | staging | 1      ",
    "version               |             |         | 1      "
  })
  void verifyServiceEnvAndVersionAreAddedAsStatsTags(String service, String env, String version) {
    int expectedSize = 6;
    if (service != null) {
      injectSysConfig(SERVICE_NAME, service);
    }
    if (env != null) {
      injectSysConfig(ENV, env);
      expectedSize += 1;
    }
    if (version != null) {
      injectSysConfig(VERSION, version);
      expectedSize += 1;
    }

    // when
    List<String> constantTags = asList(generateConstantTags(Config.get()));

    // then
    assertEquals(expectedSize, constantTags.size());
    assertTrue(constantTags.contains(LANG_STATSD_TAG + ":java"));
    assertHasTagWithPrefix(constantTags, LANG_VERSION_STATSD_TAG + ":");
    assertHasTagWithPrefix(constantTags, LANG_INTERPRETER_STATSD_TAG + ":");
    assertHasTagWithPrefix(constantTags, LANG_INTERPRETER_VENDOR_STATSD_TAG + ":");
    assertHasTagWithPrefix(constantTags, TRACER_VERSION_STATSD_TAG + ":");

    if (service == null) {
      assertHasTagWithPrefix(constantTags, "service:");
    } else {
      assertTrue(constantTags.contains("service:" + service));
    }
    if (env != null) {
      assertTrue(constantTags.contains("env:" + env));
    }
    if (version != null) {
      assertTrue(constantTags.contains("version:" + version));
    }
  }

  @Test
  @WithConfig(key = HEALTH_METRICS_ENABLED, value = "false")
  void verifyDisablingHealthMonitor() {
    // when
    MeterInstaller.installMeter();

    // then
    assertEquals(Monitoring.DISABLED, AgentMeter.monitoring());
    assertEquals(StatsDClient.NO_OP, AgentMeter.statsDClient());
  }

  private static void assertHasTagWithPrefix(List<String> tags, String prefix) {
    assertTrue(
        tags.stream().anyMatch(tag -> tag.startsWith(prefix)),
        () -> "No tag starting with '" + prefix + "' in " + tags);
  }
}

package datadog.trace.agent.jmxfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.tabletest.junit.TableTest;

class JMXFetchCollectorDecisionTest {

  @TableTest({
    "scenario                  | otlp  | internalConfigs | metricsConfigs      | websphere | expected                                                              ",
    "otlp disabled             | false | []              | []                  | false     | [jmxfetch-config.yaml]                                                ",
    "otlp disabled, websphere  | false | []              | []                  | true      | [jmxfetch-config.yaml, jmxfetch-websphere-config.yaml]                ",
    "otlp, nothing configured  | true  | []              | []                  | false     | []                                                                    ",
    "otlp, integration enabled | true  | [tomcat.yaml]   | []                  | false     | [jmxfetch-config-no-jvm-defaults.yaml]                                ",
    "otlp, metrics config      | true  | []              | [/tmp/metrics.yaml] | false     | [jmxfetch-config-no-jvm-defaults.yaml]                                ",
    "otlp, websphere only      | true  | []              | []                  | true      | [jmxfetch-websphere-config.yaml]                                      ",
    "otlp, integration and ws  | true  | [tomcat.yaml]   | []                  | true      | [jmxfetch-config-no-jvm-defaults.yaml, jmxfetch-websphere-config.yaml]"
  })
  void defaultInstanceConfigs(
      boolean otlp,
      List<String> internalConfigs,
      List<String> metricsConfigs,
      boolean websphere,
      List<String> expected) {
    assertEquals(
        expected,
        JMXFetch.defaultInstanceConfigs(otlp, internalConfigs, metricsConfigs, websphere));
  }

  @TableTest({
    "scenario             | defaultConfigs         | configs       | expected",
    "default instance     | [jmxfetch-config.yaml] | []            | true    ",
    "nothing              | []                     | []            | false   ",
    "user instance config | []                     | [my-app.yaml] | true    "
  })
  void hasJmxFetchWork(List<String> defaultConfigs, List<String> configs, boolean expected) {
    assertEquals(expected, JMXFetch.hasJmxFetchWork(defaultConfigs, configs));
  }
}

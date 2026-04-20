package datadog.trace.agent.tooling

import static datadog.trace.api.config.GeneralConfig.ENV
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME
import static datadog.trace.api.config.GeneralConfig.VERSION

import datadog.metrics.agent.AgentMeter
import datadog.metrics.api.Monitoring
import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class MeterInstallerSpecification extends DDSpecification {


  def "verify service, env, and version are added as stats tags"() {
    setup:
    def expectedSize = 6
    if (service != null) {
      injectSysConfig(SERVICE_NAME, service)
    }

    if (env != null) {
      injectSysConfig(ENV, env)
      expectedSize += 1
    }

    if (version != null) {
      injectSysConfig(VERSION, version)
      expectedSize += 1
    }

    when:
    def constantTags = MeterInstaller.generateConstantTags(new Config())

    then:
    constantTags.size() == expectedSize
    assert constantTags.any { it == MeterInstaller.LANG_STATSD_TAG + ":java" }
    assert constantTags.any { it.startsWith(MeterInstaller.LANG_VERSION_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(MeterInstaller.LANG_INTERPRETER_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(MeterInstaller.LANG_INTERPRETER_VENDOR_STATSD_TAG + ":") }
    assert constantTags.any { it.startsWith(MeterInstaller.TRACER_VERSION_STATSD_TAG + ":") }

    if (service == null) {
      assert constantTags.any { it.startsWith("service:") }
    } else {
      assert constantTags.any { it == "service:" + service }
    }

    if (env != null) {
      assert constantTags.any { it == "env:" + env }
    }

    if (version != null) {
      assert constantTags.any { it == "version:" + version }
    }

    where:
    service       | env       | version
    null          | null      | null
    "testService" | null      | null
    "testService" | "staging" | null
    "testService" | null      | "1"
    "testService" | "staging" | "1"
    null          | "staging" | null
    null          | "staging" | "1"
    null          | null      | "1"
  }

  def "verify disabling health monitor"() {
    setup:
    injectSysConfig(HEALTH_METRICS_ENABLED, "false")

    when:
    MeterInstaller.installMeter()

    then:
    AgentMeter.monitoring() == Monitoring.DISABLED
    AgentMeter.statsDClient() == StatsDClient.NO_OP
  }
}

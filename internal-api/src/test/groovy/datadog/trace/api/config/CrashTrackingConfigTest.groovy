package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS_DEFAULT

class CrashTrackingConfigTest extends DDSpecification {
  def "check default config values"() {
    when:
    def config = new Config()

    then:
    config.crashTrackingAgentless == CRASH_TRACKING_AGENTLESS_DEFAULT
    config.finalCrashTrackingTelemetryUrl == "http://localhost:8126/telemetry/proxy/api/v2/apmtelemetry"
  }


  def "check overridden config values"() {
    setup:
    System.setProperty(PREFIX + CRASH_TRACKING_AGENTLESS, "true")

    when:
    def config = new Config()

    then:
    config.crashTrackingAgentless
    config.finalCrashTrackingTelemetryUrl == "https://all-http-intake.logs.datadoghq.com/api/v2/apmtelemetry"
  }

  def "check merged tags"() {
    when:
    def config = new Config()

    then:
    with(config.mergedCrashTrackingTags) {
      size() == config.generalConfig.runtimeTags.size() +
        config.generalConfig.globalTags.size() + 3
      containsKey(datadog.trace.api.DDTags.HOST_TAG)
      containsKey(datadog.trace.api.DDTags.SERVICE_TAG)
      containsKey(datadog.trace.api.DDTags.LANGUAGE_TAG_KEY)
    }
  }
}

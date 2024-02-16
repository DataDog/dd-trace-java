package datadog.telemetry

import datadog.trace.api.Config
import spock.lang.Specification

class TelemetryClientTest extends Specification {

  def "Intake client uses correct URL for site #site"() {
    setup:
    def config = Stub(Config)
    config.getApiKey() >> "dummy-key"
    config.getAgentTimeout() >> 123
    config.getSite() >> site

    when:
    def intakeClient = TelemetryClient.buildIntakeClient(config)

    then:
    intakeClient.getUrl().toString() == expectedUrl

    where:
    site                | expectedUrl
    "datadoghq.com"     | "https://instrumentation-telemetry-intake.datadoghq.com/api/v2/apmtelemetry"
    "us3.datadoghq.com" | "https://instrumentation-telemetry-intake.us3.datadoghq.com/api/v2/apmtelemetry"
    "us5.datadoghq.com" | "https://instrumentation-telemetry-intake.us5.datadoghq.com/api/v2/apmtelemetry"
    "ap1.datadoghq.com" | "https://instrumentation-telemetry-intake.ap1.datadoghq.com/api/v2/apmtelemetry"
    "datadoghq.eu"      | "https://instrumentation-telemetry-intake.datadoghq.eu/api/v2/apmtelemetry"
    "datad0g.com"       | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  }

  def "Intake client uses CI Visibility agentless URL if configured to do so"() {
    setup:
    def config = Stub(Config)
    config.getApiKey() >> "dummy-key"
    config.getAgentTimeout() >> 123
    config.getSite() >> "datad0g.com"
    config.isCiVisibilityEnabled() >> ciVisEnabled
    config.isCiVisibilityAgentlessEnabled() >> ciVisAgentlessEnabled
    config.getCiVisibilityAgentlessUrl() >> ciVisAgentlessUrl

    when:
    def intakeClient = TelemetryClient.buildIntakeClient(config)

    then:
    intakeClient.getUrl().toString() == expectedUrl

    where:
    ciVisEnabled | ciVisAgentlessEnabled | ciVisAgentlessUrl                    | expectedUrl
    true         | true                  | "http://ci.visibility.agentless.url" | "http://ci.visibility.agentless.url/api/v2/apmtelemetry"
    false        | true                  | "http://ci.visibility.agentless.url" | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
    true         | false                 | "http://ci.visibility.agentless.url" | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
    true         | true                  | null                                 | "https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  }
}

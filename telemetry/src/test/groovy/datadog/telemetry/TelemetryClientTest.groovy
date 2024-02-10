package datadog.telemetry

import datadog.trace.api.Config
import spock.lang.Specification

class TelemetryClientTest extends Specification {

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

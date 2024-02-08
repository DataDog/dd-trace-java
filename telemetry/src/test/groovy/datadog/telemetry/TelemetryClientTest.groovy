package datadog.telemetry

import datadog.trace.api.Config
import spock.lang.Specification

class TelemetryClientTest extends Specification {

  def "Uses CI Visibility agentless URL if configured to do so"() {
    setup:
    def config = Stub(Config)
    config.getApiKey() >> "dummy-key"
    config.getAgentTimeout() >> 123
    config.isCiVisibilityEnabled() >> true
    config.isCiVisibilityAgentlessEnabled() >> true

    def agentlessUrl = "http://ci.visibility.agentless.url"
    config.getCiVisibilityAgentlessUrl() >> agentlessUrl

    when:
    def intakeClient = TelemetryClient.buildIntakeClient(config)

    then:
    intakeClient.getUrl().toString() == agentlessUrl + "/api/v2/apmtelemetry"
  }
}

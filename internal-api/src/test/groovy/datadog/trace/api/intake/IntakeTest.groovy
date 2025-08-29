package datadog.trace.api.intake

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class IntakeTest extends DDSpecification {
  def "intake URLs are generated correctly"() {
    def config = Stub(Config)
    config.getSite() >> "datadoghq.com"

    when:
    def apiUrl = Intake.API.getAgentlessUrl(config)
    def llmObsUrl = Intake.LLMOBS_API.getAgentlessUrl(config)
    def logsUrl = Intake.LOGS.getAgentlessUrl(config)

    then:
    apiUrl == "https://api.datadoghq.com/api/v2/"
    llmObsUrl  == "https://api.datadoghq.com/api/v2/"
    logsUrl == "https://http-intake.logs.datadoghq.com/api/v2/"

    when:
    config.getCiVisibilityAgentlessUrl() >> "agentless-civis"
    config.getLlMObsAgentlessUrl() >> "agentless-llmobs"
    config.getAgentlessLogSubmissionUrl() >> "agentless-log"
    apiUrl = Intake.API.getAgentlessUrl(config)
    llmObsUrl = Intake.LLMOBS_API.getAgentlessUrl(config)
    logsUrl = Intake.LOGS.getAgentlessUrl(config)

    then:
    apiUrl == "agentless-civis/api/v2/"
    llmObsUrl  == "agentless-llmobs/api/v2/"
    logsUrl == "agentless-log/api/v2/"
  }
}

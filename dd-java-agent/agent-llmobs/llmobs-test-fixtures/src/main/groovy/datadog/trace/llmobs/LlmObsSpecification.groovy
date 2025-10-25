package datadog.trace.llmobs

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Config
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.LlmObsConfig
import java.nio.file.Files

class LlmObsSpecification extends InstrumentationSpecification {

  void setupSpec() {
    def sco = new SharedCommunicationObjects()
    def config = Config.get()
    sco.createRemaining(config)
    // assert sco.configurationPoller(config) == null
    // assert sco.monitoring instanceof Monitoring.DisabledMonitoring

    LLMObsSystem.start(null, sco)
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig(LlmObsConfig.LLMOBS_ENABLED, "true") // TODO maybe extract to an override method similar to DSM/DBM (see the super impl)
  }

}

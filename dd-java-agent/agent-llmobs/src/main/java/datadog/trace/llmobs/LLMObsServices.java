package datadog.trace.llmobs;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMObsServices {

  private static final Logger logger = LoggerFactory.getLogger(LLMObsServices.class);

  final Config config;
  final BackendApi backendApi;

  LLMObsServices(Config config, SharedCommunicationObjects sco) {
    this.config = config;
    this.backendApi =
        new BackendApiFactory(config, sco).createBackendApi(BackendApiFactory.Intake.LLMOBS_API);
  }
}

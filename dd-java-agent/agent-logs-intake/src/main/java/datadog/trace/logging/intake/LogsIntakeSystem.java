package datadog.trace.logging.intake;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.logging.intake.LogsIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogsIntakeSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogsIntakeSystem.class);

  public static void start(SharedCommunicationObjects sco) {
    Config config = Config.get();
    if (!config.isAgentlessLogSubmissionEnabled()) {
      LOGGER.debug("Agentless logs intake is disabled");
      return;
    }

    BackendApiFactory apiFactory = new BackendApiFactory(config, sco);
    BackendApi backendApi = apiFactory.createBackendApi(BackendApiFactory.Intake.LOGS);
    LogsDispatcher dispatcher = new LogsDispatcher(backendApi);
    LogsWriterImpl writer = new LogsWriterImpl(config, dispatcher);
    writer.start();

    LogsIntake.registerWriter(writer);
  }

  public static void shutdown() {
    LogsIntake.shutdown();
  }
}

package datadog.trace.logging.intake;

import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.logging.intake.LogsIntake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogsIntakeSystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(LogsIntakeSystem.class);

  public static void install(SharedCommunicationObjects sco, Intake intake) {
    Config config = Config.get();
    if (!config.isAgentlessLogSubmissionEnabled() && !config.isAppLogsCollectionEnabled()) {
      LOGGER.debug("Agentless logs intake and logs capture are disabled");
      return;
    }

    BackendApiFactory apiFactory = new BackendApiFactory(config, sco);
    LogsWriterImpl writer = new LogsWriterImpl(config, apiFactory, intake);
    sco.whenReady(writer::start);

    LogsIntake.registerWriter(writer);
  }

  public static void shutdown() {
    LogsIntake.shutdown();
  }
}

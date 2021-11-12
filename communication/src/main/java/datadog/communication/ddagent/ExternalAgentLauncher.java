package datadog.communication.ddagent;

import datadog.trace.api.Config;
import datadog.trace.util.ProcessSupervisor;
import java.io.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalAgentLauncher implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ExternalAgentLauncher.class);

  private ProcessSupervisor traceProcessSupervisor;
  private ProcessSupervisor dogStatsDProcessSupervisor;

  public ExternalAgentLauncher(Config config) {
    if (config.isAzureAppServices()) {
      if (config.getTraceAgentPath() != null) {
        ProcessBuilder traceProcessBuilder = new ProcessBuilder(config.getTraceAgentPath());
        traceProcessBuilder.command().addAll(config.getTraceAgentArgs());

        traceProcessSupervisor = new ProcessSupervisor("Trace Agent", traceProcessBuilder);
      } else {
        log.warn("Trace agent path not set. Will not start trace agent process");
      }

      if (config.getDogStatsDPath() != null) {
        ProcessBuilder dogStatsDProcessBuilder = new ProcessBuilder(config.getDogStatsDPath());
        dogStatsDProcessBuilder.command().addAll(config.getDogStatsDArgs());

        dogStatsDProcessSupervisor = new ProcessSupervisor("DogStatsD", dogStatsDProcessBuilder);
      } else {
        log.warn("DogStatsD path not set. Will not start DogStatsD process");
      }
    }
  }

  @Override
  public void close() {
    if (traceProcessSupervisor != null) {
      traceProcessSupervisor.close();
    }

    if (dogStatsDProcessSupervisor != null) {
      dogStatsDProcessSupervisor.close();
    }
  }
}

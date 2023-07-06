package datadog.communication.ddagent;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.util.ProcessSupervisor;
import java.io.Closeable;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalAgentLauncher implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ExternalAgentLauncher.class);

  private static final ProcessBuilder.Redirect DISCARD =
      ProcessBuilder.Redirect.to(new File((Platform.isWindows() ? "NUL" : "/dev/null")));

  private ProcessSupervisor traceProcessSupervisor;
  private ProcessSupervisor dogStatsDProcessSupervisor;

  public ExternalAgentLauncher(Config config) {
    if (config.isAzureAppServices()) {
      if (config.getTraceAgentPath() != null) {
        ProcessBuilder traceProcessBuilder = new ProcessBuilder(config.getTraceAgentPath());
        traceProcessBuilder.redirectOutput(DISCARD);
        traceProcessBuilder.redirectError(DISCARD);
        traceProcessBuilder.command().addAll(config.getTraceAgentArgs());

        traceProcessSupervisor = new ProcessSupervisor("datadog-trace-agent", traceProcessBuilder);
      } else {
        log.warn("Trace agent path not set. Will not start trace agent process");
      }

      if (config.getDogStatsDPath() != null) {
        ProcessBuilder dogStatsDProcessBuilder = new ProcessBuilder(config.getDogStatsDPath());
        dogStatsDProcessBuilder.redirectOutput(DISCARD);
        dogStatsDProcessBuilder.redirectError(DISCARD);
        dogStatsDProcessBuilder.command().addAll(config.getDogStatsDArgs());

        dogStatsDProcessSupervisor = new ProcessSupervisor("dogstatsd", dogStatsDProcessBuilder);
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

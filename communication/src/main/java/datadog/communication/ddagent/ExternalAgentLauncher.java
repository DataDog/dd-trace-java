package datadog.communication.ddagent;

import static datadog.trace.util.ProcessSupervisor.ALWAYS_READY;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.NEVER_CHECKED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.util.ProcessSupervisor;
import java.io.Closeable;
import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalAgentLauncher implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ExternalAgentLauncher.class);

  private static final ProcessBuilder.Redirect DISCARD =
      ProcessBuilder.Redirect.to(new File((OperatingSystem.isWindows() ? "NUL" : "/dev/null")));

  private ProcessSupervisor traceProcessSupervisor;
  private ProcessSupervisor dogStatsDProcessSupervisor;

  public ExternalAgentLauncher(Config config) {
    if (config.isAzureAppServices()) {
      if (config.getTraceAgentPath() != null) {
        ProcessBuilder traceProcessBuilder = new ProcessBuilder(config.getTraceAgentPath());
        traceProcessBuilder.redirectOutput(DISCARD);
        traceProcessBuilder.redirectError(DISCARD);
        traceProcessBuilder.command().addAll(config.getTraceAgentArgs());

        traceProcessSupervisor =
            new ProcessSupervisor(
                "trace-agent", traceProcessBuilder, healthCheck(config.getAgentNamedPipe()));
      } else {
        log.warn("Trace agent path not set. Will not start trace agent process");
      }

      if (config.getDogStatsDPath() != null) {
        ProcessBuilder dogStatsDProcessBuilder = new ProcessBuilder(config.getDogStatsDPath());
        dogStatsDProcessBuilder.redirectOutput(DISCARD);
        dogStatsDProcessBuilder.redirectError(DISCARD);
        dogStatsDProcessBuilder.command().addAll(config.getDogStatsDArgs());

        dogStatsDProcessSupervisor =
            new ProcessSupervisor(
                "dogstatsd", dogStatsDProcessBuilder, healthCheck(config.getDogStatsDNamedPipe()));
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

  private static ProcessSupervisor.HealthCheck healthCheck(String pipeName) {
    return null != pipeName && !pipeName.trim().isEmpty()
        ? new NamedPipeHealthCheck(pipeName)
        : ALWAYS_READY;
  }

  static final class NamedPipeHealthCheck implements ProcessSupervisor.HealthCheck {
    private static final String NAMED_PIPE_PREFIX = "\\\\.\\pipe\\";

    private final File pipe;

    NamedPipeHealthCheck(String pipeName) {
      if (pipeName.startsWith(NAMED_PIPE_PREFIX)) {
        this.pipe = new File(pipeName);
      } else {
        this.pipe = new File(NAMED_PIPE_PREFIX + pipeName);
      }
    }

    @Override
    public ProcessSupervisor.Health run(ProcessSupervisor.Health previousHealth)
        throws InterruptedException {

      // first-time round do a more detailed check for existing bound named-pipe
      if (previousHealth == NEVER_CHECKED) {

        double delayMillis = 50;
        for (int retries = 0; retries < 7; retries++) {
          if (!pipe.exists()) {
            return READY_TO_START; // no longer bound, start our own external process
          }

          // check at increasing intervals to make sure it's bound to a healthy process
          Thread.sleep((long) delayMillis);
          delayMillis = delayMillis * 1.75;
        }

        return HEALTHY; // use existing external process
      }

      // otherwise just check that the pipe is still bound
      if (pipe.exists()) {
        return HEALTHY; // keep using external process
      } else {
        return READY_TO_START; // start our own process
      }
    }
  }
}

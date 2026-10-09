package datadog.communication.ddagent;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;
import static datadog.trace.util.ProcessSupervisor.ALWAYS_READY;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.NEVER_CHECKED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.ProcessSupervisor;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
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
                "trace-agent", traceProcessBuilder, healthCheck(config.getAgentNamedPipe(), true));
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
                "dogstatsd",
                dogStatsDProcessBuilder,
                healthCheck(config.getDogStatsDNamedPipe(), false));
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

  /**
   * @param checkResponse whether the agent answers requests on its pipe. DogStatsD never replies,
   *     so for it the pipe being present is the whole check.
   */
  private static ProcessSupervisor.HealthCheck healthCheck(String pipeName, boolean checkResponse) {
    return null != pipeName && !pipeName.trim().isEmpty()
        ? new NamedPipeHealthCheck(pipeName, checkResponse)
        : ALWAYS_READY;
  }

  static final class NamedPipeHealthCheck implements ProcessSupervisor.HealthCheck {
    private static final String NAMED_PIPE_PREFIX = "\\\\.\\pipe\\";

    private static final byte[] INFO_REQUEST =
        "GET /info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_STATUS_LINE_LENGTH = 64;
    private static final long RESPONSE_TIMEOUT_MILLIS = 2_000;

    private final File pipe;
    private final boolean checkResponse;

    // Only the supervisor thread runs the health check, so this needs no synchronization
    private FutureTask<Boolean> pendingInfoRequest;

    NamedPipeHealthCheck(String pipeName, boolean checkResponse) {
      this.checkResponse = checkResponse;
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
          if (!pipeExists()) {
            return READY_TO_START; // no longer bound, start our own external process
          }

          // check at increasing intervals to make sure it's bound to a healthy process
          Thread.sleep((long) delayMillis);
          delayMillis = delayMillis * 1.75;
        }

        return respondsOrReadyToStart(); // use existing external process if it responds
      }

      // otherwise just check that the pipe is still bound
      if (pipeExists()) {
        return respondsOrReadyToStart(); // keep using external process if it responds
      } else {
        return READY_TO_START; // start our own process
      }
    }

    private ProcessSupervisor.Health respondsOrReadyToStart() throws InterruptedException {
      return !checkResponse || agentResponds() ? HEALTHY : READY_TO_START;
    }

    /**
     * Sends a request on another thread and waits up to {@link #RESPONSE_TIMEOUT_MILLIS} for the
     * answer. Reads from a pipe cannot time out, so a request to a hung agent stays blocked until
     * the agent exits. Until then later checks fail without sending another request.
     */
    private boolean agentResponds() throws InterruptedException {
      if (pendingInfoRequest != null && !pendingInfoRequest.isDone()) {
        return false; // the last request is still waiting for an answer
      }
      FutureTask<Boolean> infoRequest = new FutureTask<>(this::sendInfoRequest);
      pendingInfoRequest = infoRequest;
      AgentThreadFactory.newAgentThread(PROCESS_SUPERVISOR, "-health-check", infoRequest, true)
          .start();
      try {
        return infoRequest.get(RESPONSE_TIMEOUT_MILLIS, MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        return false;
      }
    }

    /**
     * Sends a minimal request over a pipe handle of its own. The shared socket from {@code
     * NamedPipeSocketFactory} is not used, because the trace writer uses it and it has no timeouts.
     *
     * @return true if the agent answers with an HTTP status line, whatever the status
     */
    private boolean sendInfoRequest() throws IOException {
      try (TraceScope ignored = AgentTracer.get().muteTracing()) {
        RandomAccessFile file;
        try {
          file = new RandomAccessFile(pipe, "rw");
        } catch (FileNotFoundException e) {
          // a pipe with all instances busy cannot be asked, but an agent is serving it
          return !isPipeNotFound(e);
        }
        try {
          file.write(INFO_REQUEST);
          StringBuilder statusLine = new StringBuilder();
          for (int c = file.read();
              c != -1 && c != '\r' && statusLine.length() < MAX_STATUS_LINE_LENGTH;
              c = file.read()) {
            statusLine.append((char) c);
          }
          return statusLine.toString().startsWith("HTTP/1.");
        } finally {
          file.close();
        }
      }
    }

    /**
     * {@link File#exists()} can report false for a pipe whose instances are all busy, for example
     * under load. In that case open the pipe once to tell busy from absent.
     */
    private boolean pipeExists() {
      if (pipe.exists()) {
        return true;
      }
      if (!OperatingSystem.isWindows()) {
        return false;
      }
      try (RandomAccessFile ignored = new RandomAccessFile(pipe, "rw")) {
        return true;
      } catch (FileNotFoundException e) {
        return !isPipeNotFound(e);
      } catch (IOException e) {
        return true; // opened, then failed to close
      }
    }

    /**
     * Only "cannot find the file" (ERROR_FILE_NOT_FOUND) means no process is serving the pipe.
     * Errors such as "All pipe instances are busy" (ERROR_PIPE_BUSY) mean that one is.
     */
    private static boolean isPipeNotFound(FileNotFoundException e) {
      String message = e.getMessage();
      return message != null && message.contains("cannot find the file");
    }
  }
}

package datadog.communication.ddagent;

import static datadog.trace.util.AgentThreadFactory.AgentThread.PROCESS_SUPERVISOR;
import static datadog.trace.util.ProcessSupervisor.ALWAYS_READY;
import static datadog.trace.util.ProcessSupervisor.Health.HEALTHY;
import static datadog.trace.util.ProcessSupervisor.Health.NEVER_CHECKED;
import static datadog.trace.util.ProcessSupervisor.Health.READY_TO_START;
import static datadog.trace.util.ProcessSupervisor.Health.UNHEALTHY;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                "trace-agent", traceProcessBuilder, answersOnPipe(config.getAgentNamedPipe()));
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
                "dogstatsd", dogStatsDProcessBuilder, pipePresent(config.getDogStatsDNamedPipe()));
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

  /** Healthy while the agent's pipe is present and the agent answers a request on it. */
  private static ProcessSupervisor.HealthCheck answersOnPipe(String pipeName) {
    return healthCheck(pipeName, true);
  }

  /** Healthy while the agent's pipe is present. DogStatsD never replies on its pipe. */
  private static ProcessSupervisor.HealthCheck pipePresent(String pipeName) {
    return healthCheck(pipeName, false);
  }

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

    /** The .NET tracer's default named pipe connect timeout, {@code DD_TRACE_PIPE_TIMEOUT_MS}. */
    private static final long OPEN_TIMEOUT_MILLIS = 500;

    private final File pipe;
    private final boolean checkResponse;

    /**
     * A request that got no answer in time. Only the supervisor thread runs the health check, so
     * this needs no synchronization.
     */
    private FutureTask<Boolean> timedOutInfoRequest;

    /**
     * Created on first use. A new request is sent only once the previous one has finished, so one
     * thread is enough.
     */
    private ExecutorService requestThread;

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

        return healthOfBoundProcess(); // use existing external process if it responds
      }

      // otherwise just check that the pipe is still bound
      if (pipeExists()) {
        return healthOfBoundProcess(); // keep using external process if it responds
      } else {
        return READY_TO_START; // start our own process
      }
    }

    /** Checks the process that has the pipe, which may or may not be the supervisor's own. */
    private ProcessSupervisor.Health healthOfBoundProcess() throws InterruptedException {
      return !checkResponse || agentResponds(this::sendInfoRequest) ? HEALTHY : UNHEALTHY;
    }

    /**
     * Sends a request on the request thread and waits up to {@link #RESPONSE_TIMEOUT_MILLIS} for
     * the answer. Reads from a pipe cannot time out, so a request to a hung agent stays blocked
     * until the agent exits. Until then later checks fail without sending another request. An
     * answer that arrives after the timeout still counts at the next check, so a slow agent is not
     * restarted.
     *
     * @param request returns true if the agent answered
     */
    boolean agentResponds(Callable<Boolean> request) throws InterruptedException {
      FutureTask<Boolean> lateRequest = timedOutInfoRequest;
      if (lateRequest != null) {
        if (!lateRequest.isDone()) {
          return false; // still waiting for an answer
        }
        timedOutInfoRequest = null;
        if (answered(lateRequest)) {
          return true;
        }
      }
      FutureTask<Boolean> infoRequest = new FutureTask<>(request);
      requestThread().execute(infoRequest);
      try {
        return infoRequest.get(RESPONSE_TIMEOUT_MILLIS, MILLISECONDS);
      } catch (TimeoutException e) {
        timedOutInfoRequest = infoRequest;
        return false;
      } catch (ExecutionException e) {
        log.debug("Health check request failed", e.getCause());
        return false;
      }
    }

    private ExecutorService requestThread() {
      if (requestThread == null) {
        requestThread =
            Executors.newSingleThreadExecutor(task ->
                AgentThreadFactory.newAgentThread(PROCESS_SUPERVISOR, "-health-check", task, true));
      }
      return requestThread;
    }

    /**
     * Stops the request thread. A request still blocked on a pipe read may stay blocked until the
     * agent exits, since the read does not respond to the interrupt.
     */
    @Override
    public void close() {
      if (requestThread != null) {
        requestThread.shutdownNow();
      }
    }

    /** Returns the result of a finished request, without waiting. */
    private static boolean answered(FutureTask<Boolean> finishedRequest)
        throws InterruptedException {
      try {
        return finishedRequest.get();
      } catch (ExecutionException e) {
        log.debug("Health check request failed", e.getCause());
        return false;
      }
    }

    /**
     * Sends a minimal request over a pipe handle of its own, outside the tracer's HTTP clients.
     *
     * @return true if the agent answers with an HTTP status line, whatever the status
     */
    private boolean sendInfoRequest() throws IOException, InterruptedException {
      try (TraceScope ignored = AgentTracer.get().muteTracing()) {
        RandomAccessFile file = openPipe();
        if (file == null) {
          return false;
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
     * Opens the pipe, retrying for up to {@link #OPEN_TIMEOUT_MILLIS} like .NET's {@code
     * NamedPipeClientStream.Connect}. A pipe server creates its next pipe instance only after a
     * client connects, so an open in between fails with "All pipe instances are busy". An agent
     * that stops accepting connections keeps its pipe busy, so a pipe still busy after the retries
     * fails the check.
     *
     * @return the opened pipe, or null if it could not be opened in time
     */
    private RandomAccessFile openPipe() throws InterruptedException {
      long deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(OPEN_TIMEOUT_MILLIS);
      while (true) {
        try {
          return new RandomAccessFile(pipe, "rw");
        } catch (FileNotFoundException e) {
          if (System.nanoTime() - deadlineNanos >= 0) {
            return null;
          }
          Thread.sleep(1);
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

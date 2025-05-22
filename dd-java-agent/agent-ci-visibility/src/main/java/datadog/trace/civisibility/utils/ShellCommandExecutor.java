package datadog.trace.civisibility.utils;

import datadog.communication.util.IOUtils;
import datadog.trace.api.civisibility.telemetry.tag.ExitCode;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.util.AgentThreadFactory;
import datadog.trace.util.AgentThreadFactory.AgentThread;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellCommandExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ShellCommandExecutor.class);

  private static final int NORMAL_TERMINATION_TIMEOUT_MILLIS = 3000;

  private final File executionFolder;
  private final long timeoutMillis;
  private final Map<String, String> environment;

  public ShellCommandExecutor(File executionFolder, long timeoutMillis) {
    this(executionFolder, timeoutMillis, Collections.emptyMap());
  }

  public ShellCommandExecutor(
      File executionFolder, long timeoutMillis, Map<String, String> environment) {
    this.executionFolder = executionFolder;
    this.timeoutMillis = timeoutMillis;
    this.environment = environment;
  }

  /**
   * Executes given shell command and returns parsed output
   *
   * @param outputParser Parses that is used to process command output
   * @param command Command to be executed
   * @return Parsed command output
   * @param <T> Type of parsed command output
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for command to
   *     finish
   */
  public <T> T executeCommand(OutputParser<T> outputParser, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(outputParser, null, false, command);
  }

  /**
   * Executes given shell command, supplies to it provided input, and returns parsed output
   *
   * @param outputParser Parses that is used to process command output
   * @param input Bytes that are written to command's input stream
   * @param command Command to be executed
   * @return Parsed command output
   * @param <T> Type of parsed command output
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for command to
   *     finish
   */
  public <T> T executeCommand(OutputParser<T> outputParser, byte[] input, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(outputParser, input, false, command);
  }

  /**
   * Executes given shell command and returns parsed error stream
   *
   * @param errorParser Parses that is used to process command's error stream
   * @param command Command to be executed
   * @return Parsed command output
   * @param <T> Type of parsed command output
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for command to
   *     finish
   */
  public <T> T executeCommandReadingError(OutputParser<T> errorParser, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(errorParser, null, true, command);
  }

  private <T> T executeCommand(
      OutputParser<T> outputParser, byte[] input, boolean readFromError, String... command)
      throws IOException, TimeoutException, InterruptedException {
    Process p = null;

    // mute tracing to prevent process instrumentation from creating a span for the forked process
    try (TraceScope scope = AgentTracer.get().muteTracing()) {
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.directory(executionFolder);

      if (!environment.isEmpty()) {
        processBuilder.environment().putAll(environment);
      }

      p = processBuilder.start();

      StreamConsumer inputStreamConsumer = new StreamConsumer(p.getInputStream());
      Thread inputStreamThread =
          AgentThreadFactory.newAgentThread(
              AgentThread.CI_SHELL_COMMAND,
              "-input-stream-consumer-" + command[0],
              inputStreamConsumer,
              true);
      inputStreamThread.start();

      StreamConsumer errorStreamConsumer = new StreamConsumer(p.getErrorStream());
      Thread errorStreamThread =
          AgentThreadFactory.newAgentThread(
              AgentThread.CI_SHELL_COMMAND,
              "-error-stream-consumer-" + command[0],
              errorStreamConsumer,
              true);
      errorStreamThread.start();

      if (input != null) {
        p.getOutputStream().write(input);
        p.getOutputStream().close();
      }

      if (p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        int exitValue = p.exitValue();
        if (exitValue != 0) {
          throw new ShellCommandFailedException(
              exitValue,
              "Command '"
                  + String.join(" ", command)
                  + "' failed with exit code "
                  + exitValue
                  + ": "
                  + IOUtils.readFully(errorStreamConsumer.read(), Charset.defaultCharset()));
        }

        if (outputParser != OutputParser.IGNORE) {
          if (readFromError) {
            errorStreamThread.join(NORMAL_TERMINATION_TIMEOUT_MILLIS);
            return outputParser.parse(errorStreamConsumer.read());
          } else {
            inputStreamThread.join(NORMAL_TERMINATION_TIMEOUT_MILLIS);
            return outputParser.parse(inputStreamConsumer.read());
          }
        } else {
          return null;
        }

      } else {
        terminate(p);
        throw new TimeoutException(
            "Timeout while waiting for '"
                + String.join(" ", command)
                + "'; in "
                + executionFolder
                + "\n StdOut: \n"
                + IOUtils.readFully(inputStreamConsumer.read(), Charset.defaultCharset())
                + "\n StdErr: \n "
                + IOUtils.readFully(errorStreamConsumer.read(), Charset.defaultCharset()));
      }
    } catch (InterruptedException e) {
      terminate(p);
      throw e;
    }
  }

  private void terminate(Process p) throws InterruptedException {
    if (p == null) {
      return;
    }
    p.destroy();
    try {
      if (!p.waitFor(NORMAL_TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
      }
    } catch (InterruptedException e) {
      p.destroyForcibly();
      throw e;
    }
  }

  private static final class StreamConsumer implements Runnable {
    private final byte[] buffer = new byte[2048];
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final InputStream input;

    private StreamConsumer(InputStream input) {
      this.input = input;
    }

    @Override
    public void run() {
      try {
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
        }
      } catch (Exception e) {
        LOGGER.debug("Error while reading from process stream", e);
      }
    }

    InputStream read() {
      return new ByteArrayInputStream(output.toByteArray());
    }
  }

  public interface OutputParser<T> {
    OutputParser<Void> IGNORE = is -> null;

    T parse(InputStream inputStream) throws IOException;
  }

  public static final class ShellCommandFailedException extends IOException {
    private final int exitCode;

    public ShellCommandFailedException(int exitCode, String message) {
      super(message);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }
  }

  public static ExitCode getExitCode(Exception e) {
    if (e instanceof ShellCommandFailedException) {
      ShellCommandFailedException scfe = (ShellCommandFailedException) e;
      return ExitCode.from(scfe.getExitCode());

    } else {
      String m = e.getMessage();
      if (m != null && m.toLowerCase().contains("no such file or directory")) {
        return ExitCode.EXECUTABLE_MISSING;
      } else {
        return ExitCode.CODE_UNKNOWN;
      }
    }
  }
}

package datadog.trace.civisibility.utils;

import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ShellCommandExecutor {

  private final File executionFolder;
  private final long timeoutMillis;

  public ShellCommandExecutor(File executionFolder, long timeoutMillis) {
    this.executionFolder = executionFolder;
    this.timeoutMillis = timeoutMillis;
  }

  public <T> T executeCommand(OutputParser<T> outputParser, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(outputParser, null, false, command);
  }

  public <T> T executeCommand(OutputParser<T> outputParser, byte[] input, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(outputParser, input, false, command);
  }

  /** Executes command obtaining result from error stream */
  public <T> T executeCommandReadingError(OutputParser<T> errorParser, String... command)
      throws IOException, InterruptedException, TimeoutException {
    return executeCommand(errorParser, null, true, command);
  }

  private <T> T executeCommand(
      OutputParser<T> outputParser, byte[] input, boolean readFromError, String... command)
      throws IOException, TimeoutException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(executionFolder);

    Process p = processBuilder.start();
    try {
      if (input != null) {
        p.getOutputStream().write(input);
        p.getOutputStream().close();
      }

      if (p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        int exitValue = p.exitValue();
        if (exitValue != 0) {
          throw new IOException(
              "Command '"
                  + Strings.join(" ", command)
                  + "' failed with exit code "
                  + exitValue
                  + ": "
                  + IOUtils.readFully(p.getErrorStream(), Charset.defaultCharset()));
        }
        return outputParser.parse(readFromError ? p.getErrorStream() : p.getInputStream());

      } else {
        p.destroy();
        throw new TimeoutException(
            "Timeout while waiting for '" + Strings.join(" ", command) + "'");
      }
    } catch (InterruptedException e) {
      p.destroy();
      throw e;
    }
  }

  public interface OutputParser<T> {
    OutputParser<Void> IGNORE = is -> null;

    T parse(InputStream inputStream) throws IOException;
  }
}

package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.utils.IOUtils;
import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GitClient {

  private final String repoRoot;
  private final long timeoutMillis;

  public GitClient(String repoRoot, long timeoutMillis) {
    this.repoRoot = repoRoot;
    this.timeoutMillis = timeoutMillis;
  }

  public String getRemoteUrl(String remoteName)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
            IOUtils::readFully, "git", "config", "--get", "remote." + remoteName + ".url")
        .trim();
  }

  public List<String> getLatestCommits()
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        IOUtils::readLines, "git", "log", "--format=%H", "-n", "1000", "--since='1 month ago'");
  }

  private <T> T executeCommand(OutputParser<T> outputParser, String... command)
      throws IOException, TimeoutException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(repoRoot));

    Process p = processBuilder.start();
    try {
      if (p.waitFor(timeoutMillis, TimeUnit.SECONDS)) {
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
        return outputParser.parse(p.getInputStream());

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

  private interface OutputParser<T> {
    T parse(InputStream inputStream) throws IOException;
  }
}

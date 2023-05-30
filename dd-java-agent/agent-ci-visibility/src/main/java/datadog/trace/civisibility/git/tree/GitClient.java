package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.utils.IOUtils;
import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GitClient {

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-";

  private final String repoRoot;
  private final long timeoutMillis;

  public GitClient(String repoRoot, long timeoutMillis) {
    this.repoRoot = repoRoot;
    this.timeoutMillis = timeoutMillis;
  }

  public String getRemoteUrl(String remoteName)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
            IOUtils::readFully, null, "git", "config", "--get", "remote." + remoteName + ".url")
        .trim();
  }

  public List<String> getLatestCommits()
      throws IOException, TimeoutException, InterruptedException {
    return getCommits(1000, "1 month ago");
  }

  List<String> getCommits(int limit, String since)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        IOUtils::readLines,
        null,
        "git",
        "log",
        "--format=%H",
        "-n",
        String.valueOf(limit),
        String.format("--since='%s'", since));
  }

  public List<String> getObjects(List<String> commitsToSkip)
      throws IOException, TimeoutException, InterruptedException {
    return getObjects("HEAD", "1 month ago", commitsToSkip);
  }

  List<String> getObjects(String commit, String since, List<String> commitsToSkip)
      throws IOException, TimeoutException, InterruptedException {
    String[] command = new String[7 + commitsToSkip.size()];
    command[0] = "git";
    command[1] = "rev-list";
    command[2] = "--objects";
    command[3] = "--no-object-names";
    command[4] = "--filter=blob:none";
    command[5] = String.format("--since='%s'", since);
    command[6] = commit;

    int count = 7;
    for (String commitToSkip : commitsToSkip) {
      command[count++] = "^" + commitToSkip;
    }

    return executeCommand(IOUtils::readLines, null, command);
  }

  public Path createPackFiles(List<String> objectHashes)
      throws IOException, TimeoutException, InterruptedException {
    byte[] input = Strings.join("\n", objectHashes).getBytes(Charset.defaultCharset());

    Path tempDirectory = createTempDirectory();
    String basename = Strings.random(8);
    String path = tempDirectory.toString() + File.separator + basename;

    executeCommand(
        OutputParser.IGNORE,
        input,
        "git",
        "pack-objects",
        "--compression=9",
        "--max-pack-size=3m",
        path);
    return tempDirectory;
  }

  private Path createTempDirectory() throws IOException {
    Path repoRootDirectory = Paths.get(repoRoot);
    FileStore repoRootFileStore = Files.getFileStore(repoRootDirectory);

    Path tempDirectory = Files.createTempDirectory(DD_TEMP_DIRECTORY_PREFIX);
    FileStore tempDirectoryStore = Files.getFileStore(tempDirectory);

    if (Objects.equals(tempDirectoryStore, repoRootFileStore)) {
      return tempDirectory;
    } else {
      // default temp-file directory and repo root are located on different devices,
      // so we have to create our temp dir inside repo root
      // otherwise git command will fail
      Files.delete(tempDirectory);
      return Files.createTempDirectory(repoRootDirectory, DD_TEMP_DIRECTORY_PREFIX);
    }
  }

  private <T> T executeCommand(OutputParser<T> outputParser, byte[] input, String... command)
      throws IOException, TimeoutException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(new File(repoRoot));

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
    OutputParser<Void> IGNORE = is -> null;

    T parse(InputStream inputStream) throws IOException;
  }
}

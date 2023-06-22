package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.utils.IOUtils;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public class GitClient {

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-";

  private final String repoRoot;
  private final String latestCommitsSince;
  private final int latestCommitsLimit;
  private final ShellCommandExecutor commandExecutor;

  public GitClient(
      String repoRoot, String latestCommitsSince, int latestCommitsLimit, long timeoutMillis) {
    this.repoRoot = repoRoot;
    this.latestCommitsSince = latestCommitsSince;
    this.latestCommitsLimit = latestCommitsLimit;
    commandExecutor = new ShellCommandExecutor(new File(repoRoot), timeoutMillis);
  }

  public String getRemoteUrl(String remoteName)
      throws IOException, TimeoutException, InterruptedException {
    return commandExecutor
        .executeCommand(
            IOUtils::readFully, "git", "config", "--get", "remote." + remoteName + ".url")
        .trim();
  }

  public List<String> getLatestCommits()
      throws IOException, TimeoutException, InterruptedException {
    return commandExecutor.executeCommand(
        IOUtils::readLines,
        "git",
        "log",
        "--format=%H",
        "-n",
        String.valueOf(latestCommitsLimit),
        String.format("--since='%s'", latestCommitsSince));
  }

  public List<String> getObjects(List<String> commitsToSkip)
      throws IOException, TimeoutException, InterruptedException {
    return getObjects("HEAD", commitsToSkip);
  }

  List<String> getObjects(String commit, List<String> commitsToSkip)
      throws IOException, TimeoutException, InterruptedException {
    String[] command = new String[7 + commitsToSkip.size()];
    command[0] = "git";
    command[1] = "rev-list";
    command[2] = "--objects";
    command[3] = "--no-object-names";
    command[4] = "--filter=blob:none";
    command[5] = String.format("--since='%s'", latestCommitsSince);
    command[6] = commit;

    int count = 7;
    for (String commitToSkip : commitsToSkip) {
      command[count++] = "^" + commitToSkip;
    }

    return commandExecutor.executeCommand(IOUtils::readLines, command);
  }

  public Path createPackFiles(List<String> objectHashes)
      throws IOException, TimeoutException, InterruptedException {
    byte[] input = Strings.join("\n", objectHashes).getBytes(Charset.defaultCharset());

    Path tempDirectory = createTempDirectory();
    String basename = Strings.random(8);
    String path = tempDirectory.toString() + File.separator + basename;

    commandExecutor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
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

  @Override
  public String toString() {
    return "GitClient{" + repoRoot + "}";
  }
}

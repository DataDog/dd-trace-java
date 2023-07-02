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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Client for fetching data and performing operations on a local Git repository. */
public class GitClient {

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-";

  private final String repoRoot;
  private final String latestCommitsSince;
  private final int latestCommitsLimit;
  private final ShellCommandExecutor commandExecutor;

  /**
   * Creates a new git client
   *
   * @param repoRoot Absolute path to Git repository root
   * @param latestCommitsSince How far into the past the client should be looking when fetching Git
   *     data, e.g. {@code "1 month ago"} or {@code "2 years ago"}
   * @param latestCommitsLimit Maximum client of commits that the client should be considering when
   *     fetching commit data
   * @param timeoutMillis Timeout in milliseconds that is applied to executed Git commands
   */
  public GitClient(
      String repoRoot, String latestCommitsSince, int latestCommitsLimit, long timeoutMillis) {
    this.repoRoot = repoRoot;
    this.latestCommitsSince = latestCommitsSince;
    this.latestCommitsLimit = latestCommitsLimit;
    commandExecutor = new ShellCommandExecutor(new File(repoRoot), timeoutMillis);
  }

  /**
   * Checks whether the repo that the client is associated with is shallow
   *
   * @return {@code true} if current repo is shallow, {@code false} otherwise
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  public boolean isShallow() throws IOException, TimeoutException, InterruptedException {
    String output =
        commandExecutor
            .executeCommand(IOUtils::readFully, "git", "rev-parse", "--is-shallow-repository")
            .trim();
    return Boolean.parseBoolean(output);
  }

  /**
   * "Unshallows" the repo that the client is associated with by fetching missing commit data from
   * the server.
   *
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  public void unshallow() throws IOException, TimeoutException, InterruptedException {
    String headSha =
        commandExecutor.executeCommand(IOUtils::readFully, "git", "rev-parse", "HEAD").trim();
    String remote =
        commandExecutor
            .executeCommand(
                IOUtils::readFully,
                "git",
                "config",
                "--default",
                "origin",
                "--get",
                "clone.defaultRemoteName")
            .trim();

    // refetch data from the server for the given period of time
    commandExecutor.executeCommand(
        ShellCommandExecutor.OutputParser.IGNORE,
        "git",
        "fetch",
        String.format("--shallow-since=='%s'", latestCommitsSince),
        "--update-shallow",
        "--filter=blob:none",
        "--recurse-submodules=no",
        remote,
        headSha);
  }

  /**
   * Returns URL of the remote with the given name
   *
   * @param remoteName Name of the remote
   * @return URL of the given remote
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  public String getRemoteUrl(String remoteName)
      throws IOException, TimeoutException, InterruptedException {
    return commandExecutor
        .executeCommand(
            IOUtils::readFully, "git", "config", "--get", "remote." + remoteName + ".url")
        .trim();
  }

  /**
   * Returns SHAs of the latest commits in the current branch. Maximum number of commits and how far
   * into the past to look are configured when the client is created.
   *
   * @return SHAs of latest commits
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
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

  /**
   * Returns SHAs of all Git objects in the current branch. Lookup is started from HEAD commit.
   * Maximum number of commits and how far into the past to look are configured when the client is
   * created.
   *
   * @param commitsToSkip List of commits to skip
   * @return SHAs of relevant Git objects
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  public List<String> getObjects(
      Collection<String> commitsToSkip, Collection<String> commitsToInclude)
      throws IOException, TimeoutException, InterruptedException {
    String[] command = new String[6 + commitsToSkip.size() + commitsToInclude.size()];
    command[0] = "git";
    command[1] = "rev-list";
    command[2] = "--objects";
    command[3] = "--no-object-names";
    command[4] = "--filter=blob:none";
    command[5] = String.format("--since='%s'", latestCommitsSince);

    int count = 6;
    for (String commitToSkip : commitsToSkip) {
      command[count++] = "^" + commitToSkip;
    }
    for (String commitToInclude : commitsToInclude) {
      command[count++] = commitToInclude;
    }

    return commandExecutor.executeCommand(IOUtils::readLines, command);
  }

  /**
   * Creates Git .pack files that include provided objects. The files are created in a temporary
   * folder.
   *
   * @param objectHashes SHAs of objects that should be included in the pack files
   * @return Path to temporary folder where created pack files are located
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
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

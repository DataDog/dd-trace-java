package datadog.trace.civisibility.git.tree;

import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.civisibility.diff.LineDiff;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellGitClient implements GitClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(ShellGitClient.class);

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-";

  private final CiVisibilityMetricCollector metricCollector;
  private final String repoRoot;
  private final String latestCommitsSince;
  private final int latestCommitsLimit;
  private final ShellCommandExecutor commandExecutor;

  /**
   * Creates a new git client
   *
   * @param metricCollector Telemetry metrics collector
   * @param repoRoot Absolute path to Git repository root
   * @param latestCommitsSince How far into the past the client should be looking when fetching Git
   *     data, e.g. {@code "1 month ago"} or {@code "2 years ago"}
   * @param latestCommitsLimit Maximum client of commits that the client should be considering when
   *     fetching commit data
   * @param timeoutMillis Timeout in milliseconds that is applied to executed Git commands
   */
  ShellGitClient(
      CiVisibilityMetricCollector metricCollector,
      @Nonnull String repoRoot,
      String latestCommitsSince,
      int latestCommitsLimit,
      long timeoutMillis) {
    this.metricCollector = metricCollector;
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
  @Override
  public boolean isShallow() throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.CHECK_SHALLOW,
        () -> {
          String output =
              commandExecutor
                  .executeCommand(IOUtils::readFully, "git", "rev-parse", "--is-shallow-repository")
                  .trim();
          return Boolean.parseBoolean(output);
        });
  }

  /**
   * Returns the SHA of the head commit of the upstream (remote tracking) branch for the currently
   * checked-out local branch. If the local branch is not tracking any remote branches, a {@link
   * datadog.trace.civisibility.utils.ShellCommandExecutor.ShellCommandFailedException} exception
   * will be thrown.
   *
   * @return The name of the upstream branch if the current local branch is tracking any.
   * @throws ShellCommandExecutor.ShellCommandFailedException If the Git command fails with an error
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getUpstreamBranchSha() throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "rev-parse", "@{upstream}")
                .trim());
  }

  /**
   * "Unshallows" the repo that the client is associated with by fetching missing commit data from
   * the server.
   *
   * @param remoteCommitReference The commit to fetch from the remote repository, so local repo will
   *     be updated with this commit and its ancestors. If {@code null}, everything will be fetched.
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Override
  public void unshallow(@Nullable String remoteCommitReference)
      throws IOException, TimeoutException, InterruptedException {
    executeCommand(
        Command.UNSHALLOW,
        () -> {
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
          if (remoteCommitReference != null) {
            String headSha = getSha(remoteCommitReference);
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
          } else {
            commandExecutor.executeCommand(
                ShellCommandExecutor.OutputParser.IGNORE,
                "git",
                "fetch",
                String.format("--shallow-since=='%s'", latestCommitsSince),
                "--update-shallow",
                "--filter=blob:none",
                "--recurse-submodules=no",
                remote);
          }

          return (Void) null;
        });
  }

  /**
   * Returns the absolute path of the .git directory.
   *
   * @return absolute path
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getGitFolder() throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "rev-parse", "--absolute-git-dir")
                .trim());
  }

  /**
   * Returns the absolute path to Git repository
   *
   * @return absolute path
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getRepoRoot() throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "rev-parse", "--show-toplevel")
                .trim());
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
  @Nullable
  @Override
  public String getRemoteUrl(String remoteName)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.GET_REPOSITORY,
        () ->
            commandExecutor
                .executeCommand(
                    IOUtils::readFully, "git", "config", "--get", "remote." + remoteName + ".url")
                .trim());
  }

  /**
   * Returns current branch, or an empty string if HEAD is not pointing to a branch
   *
   * @return current branch, or an empty string if HEAD is not pointing to a branch
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getCurrentBranch() throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.GET_BRANCH,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "branch", "--show-current")
                .trim());
  }

  /**
   * Returns list of tags that provided commit points to
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return list of tags that the commit is pointing to, or empty list if there are no such tags
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nonnull
  @Override
  public List<String> getTags(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () -> {
          try {
            return commandExecutor.executeCommand(
                IOUtils::readLines, "git", "describe", "--tags", "--exact-match", commit);
          } catch (ShellCommandExecutor.ShellCommandFailedException e) {
            // if provided commit is not tagged,
            // command will fail because "--exact-match" is specified
            return Collections.emptyList();
          }
        });
  }

  /**
   * Returns SHA of the provided reference
   *
   * @param reference Reference (HEAD, branch name, etc) to check
   * @return full SHA of the provided reference
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getSha(String reference)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "rev-parse", reference)
                .trim());
  }

  /**
   * Returns full message of the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return full message of the provided commit
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getFullMessage(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%B", commit)
                .trim());
  }

  /**
   * Returns author name for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return author name for the provided commit
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getAuthorName(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%an", commit)
                .trim());
  }

  /**
   * Returns author email for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return author email for the provided commit
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getAuthorEmail(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%ae", commit)
                .trim());
  }

  /**
   * Returns author date in strict ISO 8601 format for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return author date in strict ISO 8601 format
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getAuthorDate(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%aI", commit)
                .trim());
  }

  /**
   * Returns committer name for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return committer name for the provided commit
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getCommitterName(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%cn", commit)
                .trim());
  }

  /**
   * Returns committer email for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return committer email for the provided commit
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getCommitterEmail(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%ce", commit)
                .trim());
  }

  /**
   * Returns committer date in strict ISO 8601 format for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @return committer date in strict ISO 8601 format
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getCommitterDate(String commit)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, "git", "log", "-n", "1", "--format=%cI", commit)
                .trim());
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
  @Nonnull
  @Override
  public List<String> getLatestCommits()
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.GET_LOCAL_COMMITS,
        () ->
            commandExecutor.executeCommand(
                IOUtils::readLines,
                "git",
                "log",
                "--format=%H",
                "-n",
                String.valueOf(latestCommitsLimit),
                String.format("--since='%s'", latestCommitsSince)));
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
  @Nonnull
  @Override
  public List<String> getObjects(
      Collection<String> commitsToSkip, Collection<String> commitsToInclude)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.GET_OBJECTS,
        () -> {
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
        });
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
  @Override
  public Path createPackFiles(List<String> objectHashes)
      throws IOException, TimeoutException, InterruptedException {
    return executeCommand(
        Command.PACK_OBJECTS,
        () -> {
          byte[] input = String.join("\n", objectHashes).getBytes(Charset.defaultCharset());

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
        });
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

  /**
   * Returns Git diff between two commits.
   *
   * @param baseCommit Commit SHA or reference (HEAD, branch name, etc) of the base commit
   * @param targetCommit Commit SHA or reference (HEAD, branch name, etc) of the target commit
   * @return Diff between two commits
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public LineDiff getGitDiff(String baseCommit, String targetCommit)
      throws IOException, TimeoutException, InterruptedException {
    if (Strings.isNotBlank(baseCommit) && Strings.isNotBlank(targetCommit)) {
      return executeCommand(
          Command.DIFF,
          () ->
              commandExecutor.executeCommand(
                  GitDiffParser::parse,
                  "git",
                  "diff",
                  "-U0",
                  "--word-diff=porcelain",
                  baseCommit,
                  targetCommit));
    } else {
      LOGGER.debug(
          "Base commit and/or target commit info is not available, returning empty git diff: {}/{}",
          baseCommit,
          targetCommit);
      return null;
    }
  }

  @Override
  public String toString() {
    return "GitClient{" + repoRoot + "}";
  }

  private interface GitCommand<T> {
    T execute() throws IOException, TimeoutException, InterruptedException;
  }

  private <T> T executeCommand(Command commandType, GitCommand<T> command)
      throws IOException, TimeoutException, InterruptedException {
    long startTime = System.currentTimeMillis();
    try {
      return command.execute();

    } catch (IOException | TimeoutException | InterruptedException e) {
      metricCollector.add(
          CiVisibilityCountMetric.GIT_COMMAND_ERRORS,
          1,
          commandType,
          ShellCommandExecutor.getExitCode(e));
      throw e;

    } finally {
      metricCollector.add(CiVisibilityCountMetric.GIT_COMMAND, 1, commandType);
      metricCollector.add(
          CiVisibilityDistributionMetric.GIT_COMMAND_MS,
          (int) (System.currentTimeMillis() - startTime),
          commandType);
    }
  }

  public static class Factory implements GitClient.Factory {
    private final Config config;
    private final CiVisibilityMetricCollector metricCollector;

    public Factory(Config config, CiVisibilityMetricCollector metricCollector) {
      this.config = config;
      this.metricCollector = metricCollector;
    }

    @Override
    public GitClient create(@Nullable String repoRoot) {
      long commandTimeoutMillis = config.getCiVisibilityGitCommandTimeoutMillis();
      if (repoRoot != null) {
        return new ShellGitClient(
            metricCollector, repoRoot, "1 month ago", 1000, commandTimeoutMillis);
      } else {
        LOGGER.debug("Could not determine repository root, using no-op git client");
        return NoOpGitClient.INSTANCE;
      }
    }
  }
}

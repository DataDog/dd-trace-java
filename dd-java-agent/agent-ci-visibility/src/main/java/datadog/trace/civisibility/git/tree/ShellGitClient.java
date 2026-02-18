package datadog.trace.civisibility.git.tree;

import datadog.communication.util.IOUtils;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Command;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitUtils;
import datadog.trace.api.git.PersonInfo;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellGitClient implements GitClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(ShellGitClient.class);

  private static final String DD_TEMP_DIRECTORY_PREFIX = "dd-ci-vis-";
  private static final List<String> POSSIBLE_BASE_BRANCHES =
      Arrays.asList("main", "master", "preprod", "prod", "dev", "development", "trunk");
  private static final List<String> POSSIBLE_BASE_BRANCH_PREFIXES =
      Arrays.asList("release/", "hotfix/");
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
  private static final String ORIGIN = "origin";
  private static final Pattern COMMIT_INFO_SPLIT = Pattern.compile("\",\"");

  private final CiVisibilityMetricCollector metricCollector;
  private final String repoRoot;
  private final String latestCommitsSince;
  private final int latestCommitsLimit;
  private final ShellCommandExecutor commandExecutor;
  private final String safeDirectoryOption;

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
    this.latestCommitsSince = latestCommitsSince;
    this.latestCommitsLimit = latestCommitsLimit;

    this.repoRoot = findGitRepositoryRoot(new File(repoRoot));
    this.safeDirectoryOption = "safe.directory=" + this.repoRoot;
    commandExecutor = new ShellCommandExecutor(new File(this.repoRoot), timeoutMillis);
  }

  /**
   * Finds the Git repository root by traversing upward from the given directory looking for a .git
   * directory or file (for worktrees).
   *
   * @param startDir The directory to start searching from
   * @return The absolute path to the repository root, or the original path if no .git is found
   */
  static String findGitRepositoryRoot(File startDir) {
    File current = startDir.getAbsoluteFile();
    LOGGER.debug("Finding git repository root for {}", current.getPath());
    while (current != null) {
      File gitDir = new File(current, ".git");
      if (gitDir.exists()) {
        String repoRootFound = current.getPath();
        LOGGER.debug("Git repository root found as {}", repoRootFound);
        return repoRootFound;
      }
      current = current.getParentFile();
    }
    LOGGER.debug("No .git found for repository root, defaulting to original starting directory");
    return startDir.getAbsolutePath();
  }

  /**
   * Builds a git command with the {@code safe.directory} option.
   *
   * @param gitArgs The git command arguments (everything after "git")
   * @return The complete command array including "git", "-c", "safe.directory=...", and the args
   */
  private String[] buildGitCommand(String... gitArgs) {
    String[] command = new String[gitArgs.length + 3];
    command[0] = "git";
    command[1] = "-c";
    command[2] = safeDirectoryOption;
    System.arraycopy(gitArgs, 0, command, 3, gitArgs.length);
    return command;
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
                  .executeCommand(
                      IOUtils::readFully, buildGitCommand("rev-parse", "--is-shallow-repository"))
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
                .executeCommand(IOUtils::readFully, buildGitCommand("rev-parse", "@{upstream}"))
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
          String remote = getRemoteName();

          // refetch data from the server for the given period of time
          if (remoteCommitReference != null && GitUtils.isValidRef(remoteCommitReference)) {
            String commitSha = getSha(remoteCommitReference);
            commandExecutor.executeCommand(
                ShellCommandExecutor.OutputParser.IGNORE,
                buildGitCommand(
                    "fetch",
                    "--update-shallow",
                    "--filter=blob:none",
                    "--recurse-submodules=no",
                    String.format("--shallow-since='%s'", latestCommitsSince),
                    remote,
                    commitSha));
          } else {
            commandExecutor.executeCommand(
                ShellCommandExecutor.OutputParser.IGNORE,
                buildGitCommand(
                    "fetch",
                    "--update-shallow",
                    "--filter=blob:none",
                    "--recurse-submodules=no",
                    String.format("--shallow-since='%s'", latestCommitsSince),
                    remote));
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
                .executeCommand(
                    IOUtils::readFully, buildGitCommand("rev-parse", "--absolute-git-dir"))
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
                .executeCommand(IOUtils::readFully, buildGitCommand("rev-parse", "--show-toplevel"))
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
    if (!GitUtils.isValidRef(remoteName)) {
      return null;
    }
    return executeCommand(
        Command.GET_REPOSITORY,
        () ->
            commandExecutor
                .executeCommand(
                    IOUtils::readFully,
                    buildGitCommand("config", "--get", "remote." + remoteName + ".url"))
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
                .executeCommand(IOUtils::readFully, buildGitCommand("branch", "--show-current"))
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
    if (GitUtils.isNotValidCommit(commit)) {
      return Collections.emptyList();
    }
    return executeCommand(
        Command.OTHER,
        () -> {
          try {
            return commandExecutor.executeCommand(
                IOUtils::readLines, buildGitCommand("describe", "--tags", "--exact-match", commit));
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
    if (GitUtils.isNotValidCommit(reference)) {
      return null;
    }
    return executeCommand(
        Command.OTHER,
        () ->
            commandExecutor
                .executeCommand(IOUtils::readFully, buildGitCommand("rev-parse", reference))
                .trim());
  }

  /** Checks whether the provided reference object is present or not. */
  private boolean isCommitPresent(String commitReference)
      throws IOException, TimeoutException, InterruptedException {
    if (GitUtils.isNotValidCommit(commitReference)) {
      return false;
    }
    return executeCommand(
        Command.OTHER,
        () -> {
          try {
            commandExecutor.executeCommand(
                ShellCommandExecutor.OutputParser.IGNORE,
                buildGitCommand("cat-file", "-e", commitReference + "^{commit}"));
            return true;
          } catch (ShellCommandExecutor.ShellCommandFailedException ignored) {
            return false;
          }
        });
  }

  /** Fetches provided commit object from the server. */
  private void fetchCommit(String remoteCommitReference)
      throws IOException, TimeoutException, InterruptedException {
    if (GitUtils.isNotValidCommit(remoteCommitReference)) {
      return;
    }
    executeCommand(
        Command.FETCH_COMMIT,
        () -> {
          String remote = getRemoteName();
          commandExecutor.executeCommand(
              ShellCommandExecutor.OutputParser.IGNORE,
              buildGitCommand(
                  "fetch",
                  "--filter=blob:none",
                  "--recurse-submodules=no",
                  "--no-write-fetch-head",
                  remote,
                  remoteCommitReference));

          return (Void) null;
        });
  }

  /**
   * Returns commit information for the provided commit
   *
   * @param commit Commit SHA or reference (HEAD, branch name, etc) to check
   * @param fetchIfNotPresent If the commit should be fetched from server if not present locally
   * @return commit info (sha, author info, committer info, full message)
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nonnull
  @Override
  public CommitInfo getCommitInfo(String commit, boolean fetchIfNotPresent)
      throws IOException, InterruptedException, TimeoutException {
    if (GitUtils.isNotValidCommit(commit)) {
      return CommitInfo.NOOP;
    }
    if (fetchIfNotPresent) {
      boolean isPresent = isCommitPresent(commit);
      if (!isPresent) {
        fetchCommit(commit);
      }
    }
    return executeCommand(
        Command.OTHER,
        () -> {
          String info = "";
          try {
            info =
                commandExecutor
                    .executeCommand(
                        IOUtils::readFully,
                        buildGitCommand(
                            "show",
                            commit,
                            "-s",
                            "--format=%H\",\"%an\",\"%ae\",\"%aI\",\"%cn\",\"%ce\",\"%cI\",\"%B"))
                    .trim();
          } catch (ShellCommandExecutor.ShellCommandFailedException e) {
            LOGGER.error("Failed to fetch commit info", e);
            return CommitInfo.NOOP;
          }

          String[] fields = COMMIT_INFO_SPLIT.split(info);
          if (fields.length < 8) {
            LOGGER.error("Could not parse commit info: {}", info);
            return CommitInfo.NOOP;
          }
          return new CommitInfo(
              fields[0],
              new PersonInfo(fields[1], fields[2], fields[3]),
              new PersonInfo(fields[4], fields[5], fields[6]),
              fields[7]);
        });
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
                buildGitCommand(
                    "log",
                    "--format=%H",
                    "-n",
                    String.valueOf(latestCommitsLimit),
                    String.format("--since='%s'", latestCommitsSince))));
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
          String[] gitArgs = new String[5 + commitsToSkip.size() + commitsToInclude.size()];
          gitArgs[0] = "rev-list";
          gitArgs[1] = "--objects";
          gitArgs[2] = "--no-object-names";
          gitArgs[3] = "--filter=blob:none";
          gitArgs[4] = String.format("--since='%s'", latestCommitsSince);

          int count = 5;
          for (String commitToSkip : commitsToSkip) {
            gitArgs[count++] = "^" + commitToSkip;
          }
          for (String commitToInclude : commitsToInclude) {
            gitArgs[count++] = commitToInclude;
          }

          return commandExecutor.executeCommand(IOUtils::readLines, buildGitCommand(gitArgs));
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
              buildGitCommand("pack-objects", "--compression=9", "--max-pack-size=3m", path));
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
   * Returns best effort base commit SHA for the most likely base branch in a PR.
   *
   * @param baseBranch Base branch name (if available will skip all logic that evaluates branch
   *     candidates)
   * @param settingsDefaultBranch Default branch name obtained from the settings endpoint
   * @return Base branch SHA if found or {@code null} otherwise
   * @throws IOException If an error was encountered while writing command input or reading output
   * @throws TimeoutException If timeout was reached while waiting for Git command to finish
   * @throws InterruptedException If current thread was interrupted while waiting for Git command to
   *     finish
   */
  @Nullable
  @Override
  public String getBaseCommitSha(
      @Nullable String baseBranch, @Nullable String settingsDefaultBranch)
      throws IOException, TimeoutException, InterruptedException {
    if ((baseBranch != null && !GitUtils.isValidRef(baseBranch))
        || (settingsDefaultBranch != null && !GitUtils.isValidRef(settingsDefaultBranch))) {
      return null;
    }
    return executeCommand(
        Command.BASE_COMMIT_SHA,
        () -> {
          String sourceBranch = getCurrentBranch();
          if (Strings.isBlank(sourceBranch)) {
            return null;
          }
          LOGGER.debug("Source branch: {}", sourceBranch);

          String remoteName = getRemoteName();
          LOGGER.debug("Remote name: {}", remoteName);

          if (baseBranch != null) {
            tryFetchingIfNotFoundLocally(baseBranch, remoteName);
            String fullBaseBranchName =
                remoteName + "/" + removeRemotePrefix(baseBranch, remoteName);
            return getMergeBase(fullBaseBranchName, sourceBranch);
          } else {
            return guessBestBaseBranchSha(sourceBranch, remoteName, settingsDefaultBranch);
          }
        });
  }

  String getRemoteName() throws IOException, InterruptedException, TimeoutException {
    try {
      String remote =
          commandExecutor
              .executeCommand(
                  IOUtils::readFully,
                  buildGitCommand(
                      "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"))
              .trim();

      int slashIdx = remote.indexOf('/');
      return slashIdx != -1 ? remote.substring(0, slashIdx) : remote;
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug("Error getting remote from upstream, falling back to first remote", e);
    }

    // fallback to first remote if no upstream
    try {
      List<String> remotes =
          commandExecutor.executeCommand(IOUtils::readLines, buildGitCommand("remote"));
      return remotes.get(0);
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug("Error getting remotes", e);
    }
    return ORIGIN;
  }

  @Nullable
  String guessBestBaseBranchSha(
      String sourceBranch, String remoteName, @Nullable String settingsDefaultBranch)
      throws IOException, InterruptedException, TimeoutException {
    for (String branch : POSSIBLE_BASE_BRANCHES) {
      tryFetchingIfNotFoundLocally(branch, remoteName);
    }
    if (settingsDefaultBranch != null) {
      tryFetchingIfNotFoundLocally(settingsDefaultBranch, remoteName);
    }

    List<String> candidates = getBaseBranchCandidates(settingsDefaultBranch, remoteName);
    if (candidates.isEmpty()) {
      LOGGER.debug("No base branch candidates found");
      return null;
    }

    List<BaseBranchMetric> metrics = computeBranchMetrics(candidates, sourceBranch);
    LOGGER.debug("Metrics found: {}", metrics);
    if (metrics.isEmpty()) {
      return null;
    }

    String defaultBranch =
        Strings.isNotBlank(settingsDefaultBranch)
            ? settingsDefaultBranch
            : detectDefaultBranch(remoteName);

    List<BaseBranchMetric> sortedMetrics =
        sortBaseBranchCandidates(metrics, defaultBranch, remoteName);

    for (BaseBranchMetric metric : sortedMetrics) {
      String sha = getMergeBase(metric.branch, sourceBranch);
      if (Strings.isNotBlank(sha)) {
        return sha;
      }
    }

    return null;
  }

  void tryFetchingIfNotFoundLocally(String branch, String remoteName)
      throws IOException, InterruptedException, TimeoutException {
    String shortBranchName = removeRemotePrefix(branch, remoteName);
    try {
      // check if branch exists locally as a remote ref
      commandExecutor.executeCommand(
          ShellCommandExecutor.OutputParser.IGNORE,
          buildGitCommand(
              "show-ref",
              "--verify",
              "--quiet",
              "refs/remotes/" + remoteName + "/" + shortBranchName));
      LOGGER.debug("Branch {}/{} exists locally, skipping fetch", remoteName, shortBranchName);
      return;
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug(
          "Branch {}/{} does not exist locally, checking remote", remoteName, shortBranchName);
    }

    // check if branch exists in remote
    String remoteHeads = null;
    try {
      remoteHeads =
          commandExecutor
              .executeCommand(
                  IOUtils::readFully,
                  buildGitCommand("ls-remote", "--heads", remoteName, shortBranchName))
              .trim();
    } catch (ShellCommandExecutor.ShellCommandFailedException ignored) {
    }

    if (Strings.isBlank(remoteHeads)) {
      LOGGER.debug("Branch {}/{} does not exist in remote", remoteName, shortBranchName);
      return;
    }

    // fetch latest commit for branch from remote
    LOGGER.debug("Branch {}/{} exists in remote, fetching", remoteName, shortBranchName);
    try {
      commandExecutor.executeCommand(
          ShellCommandExecutor.OutputParser.IGNORE,
          buildGitCommand("fetch", "--depth", "1", remoteName, shortBranchName));
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug("Branch {}/{} couldn't be fetched from remote", remoteName, shortBranchName, e);
    }
  }

  List<String> getBaseBranchCandidates(@Nullable String defaultBranch, String remoteName)
      throws IOException, InterruptedException, TimeoutException {
    List<String> candidates = new ArrayList<>();
    try {
      // only consider remote branches
      List<String> branches =
          commandExecutor.executeCommand(
              IOUtils::readLines,
              buildGitCommand(
                  "for-each-ref", "--format=%(refname:short)", "refs/remotes/" + remoteName));
      for (String branch : branches) {
        if (isBaseLikeBranch(branch, remoteName)
            || branchesEquals(branch, defaultBranch, remoteName)) {
          candidates.add(branch);
        }
      }
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug("Error building candidate branches", e);
    }

    return candidates;
  }

  boolean branchesEquals(String branchA, String branchB, @Nonnull String remoteName) {
    return branchA != null
        && branchB != null
        && removeRemotePrefix(branchA, remoteName).equals(removeRemotePrefix(branchB, remoteName));
  }

  String removeRemotePrefix(@Nonnull String branch, @Nonnull String remoteName) {
    if (branch.startsWith(remoteName + "/")) {
      return branch.substring(remoteName.length() + 1);
    }
    return branch;
  }

  boolean isBaseLikeBranch(@Nonnull String branch, @Nonnull String remoteName) {
    String shortBranchName = removeRemotePrefix(branch, remoteName);
    if (POSSIBLE_BASE_BRANCHES.contains(shortBranchName)) {
      return true;
    }

    for (String prefix : POSSIBLE_BASE_BRANCH_PREFIXES) {
      if (shortBranchName.startsWith(prefix)) {
        return true;
      }
    }

    return false;
  }

  @Nullable
  String detectDefaultBranch(String remoteName)
      throws IOException, InterruptedException, TimeoutException {
    try {
      String defaultRef =
          commandExecutor
              .executeCommand(
                  IOUtils::readFully,
                  buildGitCommand(
                      "symbolic-ref", "--quiet", "--short", "refs/remotes/" + remoteName + "/HEAD"))
              .trim();
      if (Strings.isNotBlank(defaultRef)) {
        return removeRemotePrefix(defaultRef, remoteName);
      }
    } catch (ShellCommandExecutor.ShellCommandFailedException ignored) {
    }

    LOGGER.debug("Could not get symbolic-ref for default branch, trying fallback");
    List<String> fallbackBranches = Arrays.asList("main", "master");
    for (String branch : fallbackBranches) {
      try {
        commandExecutor.executeCommand(
            ShellCommandExecutor.OutputParser.IGNORE,
            buildGitCommand(
                "show-ref", "--verify", "--quiet", "refs/remotes/" + remoteName + "/" + branch));
        LOGGER.debug("Found fallback default branch: {}", branch);
        return branch;
      } catch (ShellCommandExecutor.ShellCommandFailedException ignored) {
      }
    }

    LOGGER.debug("No fallback default branch found");
    return null;
  }

  static class BaseBranchMetric {
    private final String branch;
    private final int behind;
    private final int ahead;

    BaseBranchMetric(String branch, int behind, int ahead) {
      this.branch = branch;
      this.behind = behind;
      this.ahead = ahead;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof BaseBranchMetric)) return false;
      BaseBranchMetric that = (BaseBranchMetric) o;
      return behind == that.behind && ahead == that.ahead && Objects.equals(branch, that.branch);
    }

    @Override
    public int hashCode() {
      return Objects.hash(branch, behind, ahead);
    }

    @Override
    public String toString() {
      return "BaseBranchMetric{"
          + "branch='"
          + branch
          + '\''
          + ", behind="
          + behind
          + ", ahead="
          + ahead
          + '}';
    }
  }

  List<BaseBranchMetric> computeBranchMetrics(List<String> candidates, String sourceBranch)
      throws IOException, InterruptedException, TimeoutException {
    List<BaseBranchMetric> branchMetrics = new ArrayList<>();

    for (String candidate : candidates) {
      try {
        String countsResult =
            commandExecutor
                .executeCommand(
                    IOUtils::readFully,
                    buildGitCommand(
                        "rev-list", "--left-right", "--count", candidate + "..." + sourceBranch))
                .trim();

        String[] counts = WHITESPACE_PATTERN.split(countsResult);
        int behind = Integer.parseInt(counts[0]);
        int ahead = Integer.parseInt(counts[1]);
        if (behind == 0 && ahead == 0) {
          LOGGER.debug("Branch {} is up to date", candidate);
        } else {
          branchMetrics.add(new BaseBranchMetric(candidate, behind, ahead));
        }
      } catch (ShellCommandExecutor.ShellCommandFailedException ignored) {
        LOGGER.debug("Could not get metrics for candidate {}", candidate);
      }
    }

    return branchMetrics;
  }

  boolean isDefaultBranch(String branch, @Nullable String defaultBranch, String remoteName) {
    return defaultBranch != null && branchesEquals(branch, defaultBranch, remoteName);
  }

  List<BaseBranchMetric> sortBaseBranchCandidates(
      List<BaseBranchMetric> metrics, String defaultBranch, String remoteName) {
    Comparator<BaseBranchMetric> comparator =
        Comparator.comparingInt((BaseBranchMetric b) -> b.ahead)
            .thenComparing(b -> !isDefaultBranch(b.branch, defaultBranch, remoteName));

    return metrics.stream().sorted(comparator).collect(Collectors.toList());
  }

  /**
   * Returns the merge base between two commits or references
   *
   * @param base Base commit SHA or reference (HEAD, branch name, etc)
   * @param source Source commit SHA or reference (HEAD, branch name, etc)
   * @return Merge base between the two references
   */
  public String getMergeBase(@Nullable String base, @Nullable String source)
      throws IOException, InterruptedException, TimeoutException {
    if (GitUtils.isNotValidCommit(base) || GitUtils.isNotValidCommit(source)) {
      return null;
    }
    try {
      return commandExecutor
          .executeCommand(IOUtils::readFully, buildGitCommand("merge-base", base, source))
          .trim();
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug("Error calculating common ancestor for {} and {}", base, source, e);
    }
    return null;
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
    if (Strings.isBlank(baseCommit) || !GitUtils.isValidCommitSha(baseCommit)) {
      LOGGER.debug("Base commit info is not available, returning empty git diff");
      return null;
    } else if (Strings.isNotBlank(targetCommit) && GitUtils.isValidCommitSha(targetCommit)) {
      return executeCommand(
          Command.DIFF,
          () ->
              commandExecutor.executeCommand(
                  GitDiffParser::parse,
                  buildGitCommand(
                      "diff",
                      "-U0",
                      "--word-diff=porcelain",
                      "--no-prefix",
                      baseCommit,
                      targetCommit)));
    } else {
      return executeCommand(
          Command.DIFF,
          () ->
              commandExecutor.executeCommand(
                  GitDiffParser::parse,
                  buildGitCommand(
                      "diff", "-U0", "--word-diff=porcelain", "--no-prefix", baseCommit)));
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
      if (repoRoot != null && GitUtils.isValidPath(repoRoot)) {
        return new ShellGitClient(
            metricCollector, repoRoot, "1 month ago", 1000, commandTimeoutMillis);
      } else {
        LOGGER.debug("Could not determine repository root, using no-op git client");
        return NoOpGitClient.INSTANCE;
      }
    }
  }
}

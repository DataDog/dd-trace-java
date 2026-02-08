package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressFBWarnings(
    value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
    justification =
        "The GitHub Actions runner diagnostics directories have well-known absolute paths for Linux runners")
class GithubActionsInfo implements CIProviderInfo {

  private static final Logger LOGGER = LoggerFactory.getLogger(GithubActionsInfo.class);

  // https://docs.github.com/en/free-pro-team@latest/actions/reference/environment-variables#default-environment-variables
  public static final String GHACTIONS = "GITHUB_ACTION";
  public static final String GHACTIONS_PROVIDER_NAME = "github";
  public static final String GHACTIONS_PIPELINE_ID = "GITHUB_RUN_ID";
  public static final String GHACTIONS_PIPELINE_NAME = "GITHUB_WORKFLOW";
  public static final String GHACTIONS_PIPELINE_NUMBER = "GITHUB_RUN_NUMBER";
  public static final String GHACTIONS_PIPELINE_RETRY = "GITHUB_RUN_ATTEMPT";
  public static final String GHACTIONS_WORKSPACE_PATH = "GITHUB_WORKSPACE";
  public static final String GHACTIONS_REPOSITORY = "GITHUB_REPOSITORY";
  public static final String GHACTIONS_SHA = "GITHUB_SHA";
  public static final String GHACTIONS_HEAD_REF = "GITHUB_HEAD_REF";
  public static final String GHACTIONS_REF = "GITHUB_REF";
  public static final String GHACTIONS_URL = "GITHUB_SERVER_URL";
  public static final String GHACTIONS_JOB = "GITHUB_JOB";
  public static final String GITHUB_BASE_REF = "GITHUB_BASE_REF";
  public static final String GITHUB_EVENT_PATH = "GITHUB_EVENT_PATH";
  public static final String GHACTIONS_JOB_CHECK_RUN_ID = "JOB_CHECK_RUN_ID";

  static final String GHA_DIAGNOSTICS_DIR = "/home/runner/actions-runner/_diag";
  static final String GHA_DIAGNOSTICS_DIR_CACHED = "/home/runner/actions-runner/cached/_diag";
  private static final Pattern CHECK_RUN_ID_PATTERN =
      Pattern.compile("\"k\"\\s*:\\s*\"check_run_id\"\\s*,\\s*\"v\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");

  private final CiEnvironment environment;
  private final Path diagnosticsDir;
  private final Path diagnosticsDirCached;

  GithubActionsInfo(CiEnvironment environment) {
    this(environment, Paths.get(GHA_DIAGNOSTICS_DIR), Paths.get(GHA_DIAGNOSTICS_DIR_CACHED));
  }

  // for testing purposes
  GithubActionsInfo(CiEnvironment environment, Path diagnosticsDir, Path diagnosticsDirCached) {
    this.environment = environment;
    this.diagnosticsDir = diagnosticsDir;
    this.diagnosticsDirCached = diagnosticsDirCached;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        buildGitRepositoryUrl(
            filterSensitiveInfo(environment.get(GHACTIONS_URL)),
            environment.get(GHACTIONS_REPOSITORY)),
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(environment.get(GHACTIONS_SHA)));
  }

  @Override
  public CIInfo buildCIInfo() {
    String serverUrl = filterSensitiveInfo(environment.get(GHACTIONS_URL));
    String repository = environment.get(GHACTIONS_REPOSITORY);
    String pipelineId = environment.get(GHACTIONS_PIPELINE_ID);
    String commit = environment.get(GHACTIONS_SHA);

    final String pipelineUrl =
        buildPipelineUrl(
            serverUrl, repository, pipelineId, environment.get(GHACTIONS_PIPELINE_RETRY));

    // Try to get numeric job ID for better job URL
    String numericJobId = getNumericJobId();
    String jobId;
    String jobUrl;
    if (numericJobId != null) {
      jobId = numericJobId;
      jobUrl = buildJobUrlWithNumericId(serverUrl, repository, pipelineId, numericJobId);
    } else {
      jobId = environment.get(GHACTIONS_JOB);
      jobUrl = buildJobUrl(serverUrl, repository, commit);
    }

    CIInfo.Builder builder = CIInfo.builder(environment);
    return builder
        .ciProviderName(GHACTIONS_PROVIDER_NAME)
        .ciPipelineId(pipelineId)
        .ciPipelineName(environment.get(GHACTIONS_PIPELINE_NAME))
        .ciPipelineNumber(environment.get(GHACTIONS_PIPELINE_NUMBER))
        .ciPipelineUrl(pipelineUrl)
        .ciJobId(jobId)
        .ciJobName(environment.get(GHACTIONS_JOB))
        .ciJobUrl(jobUrl)
        .ciWorkspace(expandTilde(environment.get(GHACTIONS_WORKSPACE_PATH)))
        .ciEnvVars(
            GHACTIONS_URL, GHACTIONS_REPOSITORY, GHACTIONS_PIPELINE_ID, GHACTIONS_PIPELINE_RETRY)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    String baseRef = environment.get(GITHUB_BASE_REF);
    if (Strings.isBlank(baseRef)) {
      return PullRequestInfo.EMPTY;
    }

    try {
      Path eventPath = Paths.get(environment.get(GITHUB_EVENT_PATH));
      String event = new String(Files.readAllBytes(eventPath), StandardCharsets.UTF_8);

      Moshi moshi = new Moshi.Builder().build();
      JsonAdapter<Map<String, Object>> mapJsonAdapter =
          moshi.adapter(Types.newParameterizedType(Map.class, String.class, Object.class));
      Map<String, Object> eventJson = mapJsonAdapter.fromJson(event);

      String baseBranchHeadSha = null;
      String headSha = null;
      String prNumber = null;

      Map<String, Object> pullRequest = (Map<String, Object>) eventJson.get("pull_request");
      if (pullRequest != null) {
        Map<String, Object> head = (Map<String, Object>) pullRequest.get("head");
        if (head != null) {
          headSha = (String) head.get("sha");
        }

        Map<String, Object> base = (Map<String, Object>) pullRequest.get("base");
        if (base != null) {
          baseBranchHeadSha = (String) base.get("sha");
        }

        Double number = (Double) pullRequest.get("number");
        if (number != null) {
          prNumber = String.valueOf(number.intValue());
        }
      }

      return new PullRequestInfo(
          baseRef, null, baseBranchHeadSha, new CommitInfo(headSha), prNumber);

    } catch (Exception e) {
      LOGGER.warn("Error while parsing GitHub event", e);
      return new PullRequestInfo(baseRef, null, null, CommitInfo.NOOP, null);
    }
  }

  private String buildGitBranch() {
    String gitBranchOrTag = getGitBranchOrTag();
    if (!isTagReference(gitBranchOrTag)) {
      return normalizeBranch(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitTag() {
    String gitBranchOrTag = getGitBranchOrTag();
    if (isTagReference(gitBranchOrTag)) {
      return normalizeTag(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String getGitBranchOrTag() {
    String gitBranchOrTag = environment.get(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = environment.get(GHACTIONS_REF);
    }
    return gitBranchOrTag;
  }

  private String buildGitRepositoryUrl(final String host, final String repo) {
    if (repo == null || repo.isEmpty()) {
      return null;
    }

    return String.format("%s/%s.git", host, repo);
  }

  private String buildPipelineUrl(
      final String host, final String repo, final String pipelineId, final String retry) {
    if (retry != null && !retry.isEmpty()) {
      return String.format("%s/%s/actions/runs/%s/attempts/%s", host, repo, pipelineId, retry);
    } else {
      return String.format("%s/%s/actions/runs/%s", host, repo, pipelineId);
    }
  }

  private String buildJobUrl(final String host, final String repo, final String commit) {
    return String.format("%s/%s/commit/%s/checks", host, repo, commit);
  }

  private String buildJobUrlWithNumericId(
      final String host, final String repo, final String pipelineId, final String jobId) {
    return String.format("%s/%s/actions/runs/%s/job/%s", host, repo, pipelineId, jobId);
  }

  /**
   * Gets the numeric job ID for GitHub Actions.
   *
   * <p>First checks the JOB_CHECK_RUN_ID environment variable. If not present, falls back to
   * parsing GitHub Actions diagnostics files (Worker_*.log) in the runner's diagnostics directory.
   *
   * @return the numeric job ID, or null if not found
   */
  @Nullable
  private String getNumericJobId() {
    // First, check if the numeric job ID is provided via environment variable
    String jobId = environment.get(GHACTIONS_JOB_CHECK_RUN_ID);
    if (Strings.isNotBlank(jobId)) {
      return jobId;
    }

    // Fall back to parsing diagnostics files
    jobId = parseJobIdFromDirectory(diagnosticsDir);
    if (Strings.isNotBlank(jobId)) {
      return jobId;
    }

    return parseJobIdFromDirectory(diagnosticsDirCached);
  }

  @Nullable
  private String parseJobIdFromDirectory(Path directory) {
    if (!Files.isDirectory(directory)) {
      return null;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "Worker_*.log")) {
      List<Path> workerFiles = new ArrayList<>();
      for (Path path : stream) {
        workerFiles.add(path);
      }

      if (workerFiles.isEmpty()) {
        return null;
      }

      // Sort by filename in descending order to get the most recent file first
      workerFiles.sort(
          Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()));

      Path workerFile = workerFiles.get(0);
      String content = new String(Files.readAllBytes(workerFile), StandardCharsets.UTF_8);
      return parseCheckRunIdFromContent(content);
    } catch (IOException e) {
      LOGGER.debug("Error reading diagnostics directory: {}", directory, e);
      return null;
    }
  }

  /**
   * Extracts the last check_run_id value from the file content.
   *
   * <p>The JSON structure in worker logs contains: {"k":"check_run_id","v":12345.0}
   *
   * <p>Uses the last match because a single worker file might contain multiple jobs' data, and the
   * most recent job entry appears last.
   *
   * @param content the file content to parse
   * @return the check_run_id as a string, or null if not found
   */
  @Nullable
  String parseCheckRunIdFromContent(String content) {
    Matcher matcher = CHECK_RUN_ID_PATTERN.matcher(content);
    String lastMatch = null;
    while (matcher.find()) {
      String value = matcher.group(1);
      // Convert from double format (e.g., "12345.0") to integer string
      if (value.contains(".")) {
        try {
          long longValue = (long) Double.parseDouble(value);
          lastMatch = String.valueOf(longValue);
        } catch (NumberFormatException e) {
          LOGGER.debug("Error parsing check_run_id value: {}", value, e);
        }
      } else {
        lastMatch = value;
      }
    }
    return lastMatch;
  }

  @Override
  public Provider getProvider() {
    return Provider.GITHUBACTIONS;
  }
}

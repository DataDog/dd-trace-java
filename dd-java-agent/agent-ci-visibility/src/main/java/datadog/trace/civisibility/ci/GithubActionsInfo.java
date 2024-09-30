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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class GithubActionsInfo implements CIProviderInfo {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnknownCIInfo.class);

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

  public static final String GIT_PULL_REQUEST_BASE_BRANCH = "git.pull_request.base_branch";
  public static final String GIT_PULL_REQUEST_BASE_BRANCH_SHA = "git.pull_request.base_branch_sha";
  public static final String GIT_COMMIT_HEAD_SHA = "git.commit.head_sha";

  private final CiEnvironment environment;

  GithubActionsInfo(CiEnvironment environment) {
    this.environment = environment;
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
    final String pipelineUrl =
        buildPipelineUrl(
            filterSensitiveInfo(environment.get(GHACTIONS_URL)),
            environment.get(GHACTIONS_REPOSITORY),
            environment.get(GHACTIONS_PIPELINE_ID),
            environment.get(GHACTIONS_PIPELINE_RETRY));
    final String jobUrl =
        buildJobUrl(
            filterSensitiveInfo(environment.get(GHACTIONS_URL)),
            environment.get(GHACTIONS_REPOSITORY),
            environment.get(GHACTIONS_SHA));

    CIInfo.Builder builder = CIInfo.builder(environment);

    setAdditionalTagsIfApplicable(builder);

    return builder
        .ciProviderName(GHACTIONS_PROVIDER_NAME)
        .ciPipelineId(environment.get(GHACTIONS_PIPELINE_ID))
        .ciPipelineName(environment.get(GHACTIONS_PIPELINE_NAME))
        .ciPipelineNumber(environment.get(GHACTIONS_PIPELINE_NUMBER))
        .ciPipelineUrl(pipelineUrl)
        .ciJobName(environment.get(GHACTIONS_JOB))
        .ciJobUrl(jobUrl)
        .ciWorkspace(expandTilde(environment.get(GHACTIONS_WORKSPACE_PATH)))
        .ciEnvVars(
            GHACTIONS_URL, GHACTIONS_REPOSITORY, GHACTIONS_PIPELINE_ID, GHACTIONS_PIPELINE_RETRY)
        .build();
  }

  private void setAdditionalTagsIfApplicable(CIInfo.Builder builder) {
    String baseRef = environment.get(GITHUB_BASE_REF);
    if (!Strings.isNotBlank(baseRef)) {
      return;
    }

    try {
      Map<String, String> additionalTags = new HashMap<>();
      additionalTags.put(GIT_PULL_REQUEST_BASE_BRANCH, baseRef);

      Path eventPath = Paths.get(environment.get(GITHUB_EVENT_PATH));
      String event = new String(Files.readAllBytes(eventPath), StandardCharsets.UTF_8);

      Moshi moshi = new Moshi.Builder().build();
      JsonAdapter<Map<String, Object>> mapJsonAdapter =
          moshi.adapter(Types.newParameterizedType(Map.class, String.class, Object.class));
      Map<String, Object> eventJson = mapJsonAdapter.fromJson(event);

      Map<String, Object> pullRequest = (Map<String, Object>) eventJson.get("pull_request");
      if (pullRequest != null) {
        Map<String, Object> head = (Map<String, Object>) pullRequest.get("head");
        if (head != null) {
          String headSha = (String) head.get("sha");
          additionalTags.put(GIT_COMMIT_HEAD_SHA, headSha);
        }

        Map<String, Object> base = (Map<String, Object>) pullRequest.get("base");
        if (base != null) {
          String baseSha = (String) base.get("sha");
          additionalTags.put(GIT_PULL_REQUEST_BASE_BRANCH_SHA, baseSha);
        }
      }

      builder.additionalTags(additionalTags);

    } catch (Exception e) {
      LOGGER.warn("Error while parsing GitHub event", e);
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

  @Override
  public Provider getProvider() {
    return Provider.GITHUBACTIONS;
  }
}

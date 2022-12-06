package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;

class GithubActionsInfo extends CIProviderInfo {

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

  @Override
  protected GitInfo buildCIGitInfo() {
    return new GitInfo(
        buildGitRepositoryUrl(System.getenv(GHACTIONS_URL), System.getenv(GHACTIONS_REPOSITORY)),
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(System.getenv(GHACTIONS_SHA)));
  }

  @Override
  protected CIInfo buildCIInfo() {
    final String pipelineUrl =
        buildPipelineUrl(
            System.getenv(GHACTIONS_URL),
            System.getenv(GHACTIONS_REPOSITORY),
            System.getenv(GHACTIONS_PIPELINE_ID),
            System.getenv(GHACTIONS_PIPELINE_RETRY));
    final String jobUrl =
        buildJobUrl(
            System.getenv(GHACTIONS_URL),
            System.getenv(GHACTIONS_REPOSITORY),
            System.getenv(GHACTIONS_SHA));

    return CIInfo.builder()
        .ciProviderName(GHACTIONS_PROVIDER_NAME)
        .ciPipelineId(System.getenv(GHACTIONS_PIPELINE_ID))
        .ciPipelineName(System.getenv(GHACTIONS_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(GHACTIONS_PIPELINE_NUMBER))
        .ciPipelineUrl(pipelineUrl)
        .ciJobUrl(jobUrl)
        .ciWorkspace(expandTilde(System.getenv(GHACTIONS_WORKSPACE_PATH)))
        .ciEnvVars(
            GHACTIONS_URL, GHACTIONS_REPOSITORY, GHACTIONS_PIPELINE_ID, GHACTIONS_PIPELINE_RETRY)
        .build();
  }

  private String buildGitTag() {
    String gitBranchOrTag = System.getenv(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(GHACTIONS_REF);
    }

    if (gitBranchOrTag != null && gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitBranch() {
    String gitBranchOrTag = System.getenv(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(GHACTIONS_REF);
    }

    if (gitBranchOrTag != null && !gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
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
}

package datadog.trace.civisibility;

import static datadog.trace.civisibility.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.civisibility.git.GitUtils.normalizeRef;
import static datadog.trace.civisibility.utils.PathUtils.expandTilde;

import datadog.trace.civisibility.git.CommitInfo;
import datadog.trace.civisibility.git.GitInfo;

class CircleCIInfo implements CIProviderInfo {

  // https://circleci.com/docs/2.0/env-vars/#built-in-environment-variables
  public static final String CIRCLECI = "CIRCLECI";
  public static final String CIRCLECI_PROVIDER_NAME = "circleci";
  public static final String CIRCLECI_PIPELINE_ID = "CIRCLE_WORKFLOW_ID";
  public static final String CIRCLECI_PIPELINE_NAME = "CIRCLE_PROJECT_REPONAME";
  public static final String CIRCLECI_BUILD_URL = "CIRCLE_BUILD_URL";
  public static final String CIRCLECI_BUILD_NUM = "CIRCLE_BUILD_NUM";
  public static final String CIRCLECI_WORKSPACE_PATH = "CIRCLE_WORKING_DIRECTORY";
  public static final String CIRCLECI_GIT_REPOSITORY_URL = "CIRCLE_REPOSITORY_URL";
  public static final String CIRCLECI_GIT_COMMIT = "CIRCLE_SHA1";
  public static final String CIRCLECI_GIT_BRANCH = "CIRCLE_BRANCH";
  public static final String CIRCLECI_GIT_TAG = "CIRCLE_TAG";
  public static final String CIRCLECI_JOB_NAME = "CIRCLE_JOB";

  @Override
  public GitInfo buildCIGitInfo() {
    final String gitTag = normalizeRef(System.getenv(CIRCLECI_GIT_TAG));
    return new GitInfo(
        filterSensitiveInfo(System.getenv(CIRCLECI_GIT_REPOSITORY_URL)),
        buildGitBranch(gitTag),
        gitTag,
        new CommitInfo(System.getenv(CIRCLECI_GIT_COMMIT)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String pipelineId = System.getenv(CIRCLECI_PIPELINE_ID);
    return CIInfo.builder()
        .ciProviderName(CIRCLECI_PROVIDER_NAME)
        .ciPipelineId(pipelineId)
        .ciPipelineName(System.getenv(CIRCLECI_PIPELINE_NAME))
        .ciPipelineUrl(buildPipelineUrl(pipelineId))
        .ciJobName(System.getenv(CIRCLECI_JOB_NAME))
        .ciJobUrl(System.getenv(CIRCLECI_BUILD_URL))
        .ciWorkspace(expandTilde(System.getenv(CIRCLECI_WORKSPACE_PATH)))
        .ciEnvVars(CIRCLECI_PIPELINE_ID, CIRCLECI_BUILD_NUM)
        .build();
  }

  @Override
  public boolean isCI() {
    return true;
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    return normalizeRef(System.getenv(CIRCLECI_GIT_BRANCH));
  }

  private String buildPipelineUrl(final String pipelineId) {
    if (pipelineId == null) {
      return null;
    }

    return String.format("https://app.circleci.com/pipelines/workflows/%s", pipelineId);
  }
}

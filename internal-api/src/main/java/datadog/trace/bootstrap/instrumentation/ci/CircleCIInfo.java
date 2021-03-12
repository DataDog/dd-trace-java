package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;

class CircleCIInfo extends CIProviderInfo {

  // https://circleci.com/docs/2.0/env-vars/#built-in-environment-variables
  public static final String CIRCLECI = "CIRCLECI";
  public static final String CIRCLECI_PROVIDER_NAME = "circleci";
  public static final String CIRCLECI_PIPELINE_ID = "CIRCLE_WORKFLOW_ID";
  public static final String CIRCLECI_PIPELINE_NAME = "CIRCLE_PROJECT_REPONAME";
  public static final String CIRCLECI_PIPELINE_NUMBER = "CIRCLE_BUILD_NUM";
  public static final String CIRCLECI_BUILD_URL = "CIRCLE_BUILD_URL";
  public static final String CIRCLECI_WORKSPACE_PATH = "CIRCLE_WORKING_DIRECTORY";
  public static final String CIRCLECI_GIT_REPOSITORY_URL = "CIRCLE_REPOSITORY_URL";
  public static final String CIRCLECI_GIT_COMMIT = "CIRCLE_SHA1";
  public static final String CIRCLECI_GIT_BRANCH = "CIRCLE_BRANCH";
  public static final String CIRCLECI_GIT_TAG = "CIRCLE_TAG";

  @Override
  protected GitInfo buildCIGitInfo() {
    final String gitTag = normalizeRef(System.getenv(CIRCLECI_GIT_TAG));
    return new GitInfo(
        filterSensitiveInfo(System.getenv(CIRCLECI_GIT_REPOSITORY_URL)),
        buildGitBranch(gitTag),
        gitTag,
        new CommitInfo(System.getenv(CIRCLECI_GIT_COMMIT)));
  }

  @Override
  protected CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(CIRCLECI_PROVIDER_NAME)
        .ciPipelineId(System.getenv(CIRCLECI_PIPELINE_ID))
        .ciPipelineName(System.getenv(CIRCLECI_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(CIRCLECI_PIPELINE_NUMBER))
        .ciPipelineUrl(System.getenv(CIRCLECI_BUILD_URL))
        .ciJobUrl(System.getenv(CIRCLECI_BUILD_URL))
        .ciWorkspace(expandTilde(System.getenv(CIRCLECI_WORKSPACE_PATH)))
        .build();
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    return normalizeRef(System.getenv(CIRCLECI_GIT_BRANCH));
  }
}

package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

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
  public static final String CIRCLECI_PR_NUMBER = "CIRCLE_PR_NUMBER";

  private final CiEnvironment environment;

  CircleCIInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(CIRCLECI_GIT_REPOSITORY_URL)),
        normalizeBranch(environment.get(CIRCLECI_GIT_BRANCH)),
        normalizeTag(environment.get(CIRCLECI_GIT_TAG)),
        new CommitInfo(environment.get(CIRCLECI_GIT_COMMIT)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String pipelineId = environment.get(CIRCLECI_PIPELINE_ID);
    return CIInfo.builder(environment)
        .ciProviderName(CIRCLECI_PROVIDER_NAME)
        .ciPipelineId(pipelineId)
        .ciPipelineName(environment.get(CIRCLECI_PIPELINE_NAME))
        .ciPipelineUrl(buildPipelineUrl(pipelineId))
        .ciJobName(environment.get(CIRCLECI_JOB_NAME))
        .ciJobUrl(environment.get(CIRCLECI_BUILD_URL))
        .ciWorkspace(expandTilde(environment.get(CIRCLECI_WORKSPACE_PATH)))
        .ciEnvVars(CIRCLECI_PIPELINE_ID, CIRCLECI_BUILD_NUM)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        null, null, null, CommitInfo.NOOP, environment.get(CIRCLECI_PR_NUMBER));
  }

  private String buildPipelineUrl(final String pipelineId) {
    if (pipelineId == null) {
      return null;
    }

    return String.format("https://app.circleci.com/pipelines/workflows/%s", pipelineId);
  }

  @Override
  public Provider getProvider() {
    return Provider.CIRCLECI;
  }
}

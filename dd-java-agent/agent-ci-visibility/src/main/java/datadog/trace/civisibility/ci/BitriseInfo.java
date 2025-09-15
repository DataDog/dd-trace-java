package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

class BitriseInfo implements CIProviderInfo {

  // https://devcenter.bitrise.io/en/references/available-environment-variables.html
  public static final String BITRISE = "BITRISE_BUILD_SLUG";
  public static final String BITRISE_PROVIDER_NAME = "bitrise";
  public static final String BITRISE_PIPELINE_ID = "BITRISE_BUILD_SLUG";
  public static final String BITRISE_PIPELINE_NAME = "BITRISE_TRIGGERED_WORKFLOW_ID";
  public static final String BITRISE_PIPELINE_NUMBER = "BITRISE_BUILD_NUMBER";
  public static final String BITRISE_PIPELINE_URL = "BITRISE_BUILD_URL";
  public static final String BITRISE_WORKSPACE_PATH = "BITRISE_SOURCE_DIR";
  public static final String BITRISE_GIT_REPOSITORY_URL = "GIT_REPOSITORY_URL";
  public static final String BITRISE_GIT_PR_COMMIT = "BITRISE_GIT_COMMIT";
  public static final String BITRISE_GIT_COMMIT = "GIT_CLONE_COMMIT_HASH";
  public static final String BITRISE_GIT_BRANCH = "BITRISE_GIT_BRANCH";
  public static final String BITRISE_GIT_TAG = "BITRISE_GIT_TAG";
  public static final String BITRISE_GIT_MESSAGE = "BITRISE_GIT_MESSAGE";
  public static final String BITRISE_GIT_AUTHOR_NAME = "GIT_CLONE_COMMIT_AUTHOR_NAME";
  public static final String BITRISE_GIT_AUTHOR_EMAIL = "GIT_CLONE_COMMIT_AUTHOR_EMAIL";
  public static final String BITRISE_GIT_COMMITER_NAME = "GIT_CLONE_COMMIT_COMMITER_NAME";
  public static final String BITRISE_GIT_COMMITER_EMAIL = "GIT_CLONE_COMMIT_COMMITER_EMAIL";
  public static final String BITRISE_GIT_BRANCH_DEST = "BITRISEIO_GIT_BRANCH_DEST";
  public static final String BITRISE_PR_NUMBER = "BITRISE_PULL_REQUEST";

  private final CiEnvironment environment;

  BitriseInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(BITRISE_GIT_REPOSITORY_URL)),
        normalizeBranch(environment.get(BITRISE_GIT_BRANCH)),
        normalizeTag(environment.get(BITRISE_GIT_TAG)),
        new CommitInfo(
            buildGitCommit(),
            new PersonInfo(
                environment.get(BITRISE_GIT_AUTHOR_NAME),
                environment.get(BITRISE_GIT_AUTHOR_EMAIL)),
            new PersonInfo(
                environment.get(BITRISE_GIT_COMMITER_NAME),
                environment.get(BITRISE_GIT_COMMITER_EMAIL)),
            environment.get(BITRISE_GIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(BITRISE_PROVIDER_NAME)
        .ciPipelineId(environment.get(BITRISE_PIPELINE_ID))
        .ciPipelineName(environment.get(BITRISE_PIPELINE_NAME))
        .ciPipelineNumber(environment.get(BITRISE_PIPELINE_NUMBER))
        .ciPipelineUrl(environment.get(BITRISE_PIPELINE_URL))
        .ciWorkspace(expandTilde(environment.get(BITRISE_WORKSPACE_PATH)))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(BITRISE_GIT_BRANCH_DEST)),
        null,
        null,
        CommitInfo.NOOP,
        environment.get(BITRISE_PR_NUMBER));
  }

  private String buildGitCommit() {
    final String fromCommit = environment.get(BITRISE_GIT_PR_COMMIT);
    if (fromCommit != null && !fromCommit.isEmpty()) {
      return fromCommit;
    } else {
      return environment.get(BITRISE_GIT_COMMIT);
    }
  }

  @Override
  public Provider getProvider() {
    return Provider.BITRISE;
  }
}

package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

class BuddyInfo implements CIProviderInfo {

  // https://buddy.works/docs/pipelines/environment-variables
  public static final String BUDDY = "BUDDY";
  public static final String BUDDY_PROVIDER_NAME = "buddy";
  public static final String BUDDY_PIPELINE_ID = "BUDDY_PIPELINE_ID";
  public static final String BUDDY_PIPELINE_NAME = "BUDDY_PIPELINE_NAME";
  public static final String BUDDY_PIPELINE_EXECUTION_ID = "BUDDY_EXECUTION_ID";
  public static final String BUDDY_PIPELINE_EXECUTION_URL = "BUDDY_EXECUTION_URL";
  public static final String BUDDY_GIT_REPOSITORY_URL = "BUDDY_SCM_URL";
  public static final String BUDDY_GIT_COMMIT = "BUDDY_EXECUTION_REVISION";
  public static final String BUDDY_GIT_BRANCH = "BUDDY_EXECUTION_BRANCH";
  public static final String BUDDY_GIT_TAG = "BUDDY_EXECUTION_TAG";
  public static final String BUDDY_GIT_COMMIT_MESSAGE = "BUDDY_EXECUTION_REVISION_MESSAGE";
  public static final String BUDDY_GIT_COMMIT_AUTHOR = "BUDDY_EXECUTION_REVISION_COMMITTER_NAME";
  public static final String BUDDY_GIT_COMMIT_EMAIL = "BUDDY_EXECUTION_REVISION_COMMITTER_EMAIL";
  public static final String BUDDY_RUN_PR_BASE_BRANCH = "BUDDY_RUN_PR_BASE_BRANCH";

  private final CiEnvironment environment;

  BuddyInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(BUDDY_GIT_REPOSITORY_URL)),
        normalizeBranch(environment.get(BUDDY_GIT_BRANCH)),
        normalizeTag(environment.get(BUDDY_GIT_TAG)),
        new CommitInfo(
            environment.get(BUDDY_GIT_COMMIT),
            PersonInfo.NOOP,
            buildGitCommiter(),
            environment.get(BUDDY_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    String pipelineNumber = environment.get(BUDDY_PIPELINE_EXECUTION_ID);
    return CIInfo.builder(environment)
        .ciProviderName(BUDDY_PROVIDER_NAME)
        .ciPipelineId(getPipelineId(pipelineNumber))
        .ciPipelineName(environment.get(BUDDY_PIPELINE_NAME))
        .ciPipelineNumber(pipelineNumber)
        .ciPipelineUrl(environment.get(BUDDY_PIPELINE_EXECUTION_URL))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(BUDDY_RUN_PR_BASE_BRANCH)), null, null);
  }

  private String getPipelineId(String pipelineNumber) {
    String pipelineId = environment.get(BUDDY_PIPELINE_ID);
    if (pipelineId == null) {
      return pipelineNumber;
    }
    if (pipelineNumber == null) {
      return pipelineId;
    }
    return String.format("%s/%s", pipelineId, pipelineNumber);
  }

  private PersonInfo buildGitCommiter() {
    return new PersonInfo(
        environment.get(BUDDY_GIT_COMMIT_AUTHOR), environment.get(BUDDY_GIT_COMMIT_EMAIL));
  }

  @Override
  public Provider getProvider() {
    return Provider.BUDDYCI;
  }
}

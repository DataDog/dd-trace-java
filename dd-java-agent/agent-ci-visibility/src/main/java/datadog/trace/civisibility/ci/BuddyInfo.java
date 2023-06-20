package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;

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

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(System.getenv(BUDDY_GIT_REPOSITORY_URL)),
        normalizeBranch(System.getenv(BUDDY_GIT_BRANCH)),
        normalizeTag(System.getenv(BUDDY_GIT_TAG)),
        new CommitInfo(
            System.getenv(BUDDY_GIT_COMMIT),
            PersonInfo.NOOP,
            buildGitCommiter(),
            System.getenv(BUDDY_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    String pipelineNumber = System.getenv(BUDDY_PIPELINE_EXECUTION_ID);
    return CIInfo.builder()
        .ciProviderName(BUDDY_PROVIDER_NAME)
        .ciPipelineId(getPipelineId(pipelineNumber))
        .ciPipelineName(System.getenv(BUDDY_PIPELINE_NAME))
        .ciPipelineNumber(pipelineNumber)
        .ciPipelineUrl(System.getenv(BUDDY_PIPELINE_EXECUTION_URL))
        .build();
  }

  private static String getPipelineId(String pipelineNumber) {
    String pipelineId = System.getenv(BUDDY_PIPELINE_ID);
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
        System.getenv(BUDDY_GIT_COMMIT_AUTHOR), System.getenv(BUDDY_GIT_COMMIT_EMAIL));
  }
}

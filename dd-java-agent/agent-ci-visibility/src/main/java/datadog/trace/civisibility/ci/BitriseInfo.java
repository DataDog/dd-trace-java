package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;

class BitriseInfo implements CIProviderInfo {

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

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(System.getenv(BITRISE_GIT_REPOSITORY_URL)),
        normalizeBranch(System.getenv(BITRISE_GIT_BRANCH)),
        normalizeTag(System.getenv(BITRISE_GIT_TAG)),
        new CommitInfo(
            buildGitCommit(),
            new PersonInfo(
                System.getenv(BITRISE_GIT_AUTHOR_NAME), System.getenv(BITRISE_GIT_AUTHOR_EMAIL)),
            new PersonInfo(
                System.getenv(BITRISE_GIT_COMMITER_NAME),
                System.getenv(BITRISE_GIT_COMMITER_EMAIL)),
            System.getenv(BITRISE_GIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(BITRISE_PROVIDER_NAME)
        .ciPipelineId(System.getenv(BITRISE_PIPELINE_ID))
        .ciPipelineName(System.getenv(BITRISE_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(BITRISE_PIPELINE_NUMBER))
        .ciPipelineUrl(System.getenv(BITRISE_PIPELINE_URL))
        .ciWorkspace(expandTilde(System.getenv(BITRISE_WORKSPACE_PATH)))
        .build();
  }

  private String buildGitCommit() {
    final String fromCommit = System.getenv(BITRISE_GIT_PR_COMMIT);
    if (fromCommit != null && !fromCommit.isEmpty()) {
      return fromCommit;
    } else {
      return System.getenv(BITRISE_GIT_COMMIT);
    }
  }
}

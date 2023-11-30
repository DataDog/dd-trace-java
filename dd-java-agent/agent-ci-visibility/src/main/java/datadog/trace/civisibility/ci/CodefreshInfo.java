package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;

public class CodefreshInfo implements CIProviderInfo {
  public static final String CODEFRESH = "CF_BUILD_ID";
  public static final String CODEFRESH_PROVIDER_NAME = "codefresh";
  public static final String CF_STEP_NAME = "CF_STEP_NAME";
  public static final String CF_PIPELINE_NAME = "CF_PIPELINE_NAME";
  public static final String CF_BUILD_URL = "CF_BUILD_URL";
  private static final String CF_BRANCH = "CF_BRANCH";
  private static final String CF_REVISION = "CF_REVISION";
  private static final String CF_COMMIT_MESSAGE = "CF_COMMIT_MESSAGE";
  private static final String CF_COMMIT_AUTHOR = "CF_COMMIT_AUTHOR";

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        null,
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(
            System.getenv(CF_REVISION),
            new PersonInfo(System.getenv(CF_COMMIT_AUTHOR), null),
            PersonInfo.NOOP,
            System.getenv(CF_COMMIT_MESSAGE)));
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

  private static String getGitBranchOrTag() {
    return System.getenv(CF_BRANCH);
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(CODEFRESH_PROVIDER_NAME)
        .ciPipelineId(System.getenv(CODEFRESH))
        .ciPipelineName(System.getenv(CF_PIPELINE_NAME))
        .ciPipelineUrl(System.getenv(CF_BUILD_URL))
        .ciJobName(System.getenv(CF_STEP_NAME))
        .ciEnvVars(CODEFRESH)
        .build();
  }
}

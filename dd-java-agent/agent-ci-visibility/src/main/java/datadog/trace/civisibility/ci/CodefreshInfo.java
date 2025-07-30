package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

public class CodefreshInfo implements CIProviderInfo {

  // https://codefresh.io/docs/docs/pipelines/variables/#system-variables
  public static final String CODEFRESH = "CF_BUILD_ID";
  public static final String CODEFRESH_PROVIDER_NAME = "codefresh";
  public static final String CF_STEP_NAME = "CF_STEP_NAME";
  public static final String CF_PIPELINE_NAME = "CF_PIPELINE_NAME";
  public static final String CF_BUILD_URL = "CF_BUILD_URL";
  private static final String CF_BRANCH = "CF_BRANCH";
  private static final String CF_REVISION = "CF_REVISION";
  private static final String CF_COMMIT_MESSAGE = "CF_COMMIT_MESSAGE";
  private static final String CF_COMMIT_AUTHOR = "CF_COMMIT_AUTHOR";
  private static final String CF_PULL_REQUEST_NUMBER = "CF_PULL_REQUEST_NUMBER";
  private static final String CF_PULL_REQUEST_TARGET_BRANCH = "CF_PULL_REQUEST_TARGET";

  private final CiEnvironment environment;

  CodefreshInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        null,
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(
            environment.get(CF_REVISION),
            new PersonInfo(environment.get(CF_COMMIT_AUTHOR), null),
            PersonInfo.NOOP,
            environment.get(CF_COMMIT_MESSAGE)));
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(CF_PULL_REQUEST_TARGET_BRANCH)),
        null,
        null,
        CommitInfo.NOOP,
        environment.get(CF_PULL_REQUEST_NUMBER));
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
    return environment.get(CF_BRANCH);
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(CODEFRESH_PROVIDER_NAME)
        .ciPipelineId(environment.get(CODEFRESH))
        .ciPipelineName(environment.get(CF_PIPELINE_NAME))
        .ciPipelineUrl(environment.get(CF_BUILD_URL))
        .ciJobName(environment.get(CF_STEP_NAME))
        .ciEnvVars(CODEFRESH)
        .build();
  }

  @Override
  public Provider getProvider() {
    return Provider.CODEFRESH;
  }
}

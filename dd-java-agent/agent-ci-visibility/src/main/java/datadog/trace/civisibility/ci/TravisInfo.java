package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

class TravisInfo implements CIProviderInfo {

  // https://docs.travis-ci.com/user/environment-variables/#default-environment-variables
  public static final String TRAVIS = "TRAVIS";
  public static final String TRAVIS_PROVIDER_NAME = "travisci";
  public static final String TRAVIS_PIPELINE_ID = "TRAVIS_BUILD_ID";
  public static final String TRAVIS_PIPELINE_NUMBER = "TRAVIS_BUILD_NUMBER";
  public static final String TRAVIS_PIPELINE_URL = "TRAVIS_BUILD_WEB_URL";
  public static final String TRAVIS_JOB_URL = "TRAVIS_JOB_WEB_URL";
  public static final String TRAVIS_WORKSPACE_PATH = "TRAVIS_BUILD_DIR";
  public static final String TRAVIS_REPOSITORY_SLUG = "TRAVIS_REPO_SLUG";
  public static final String TRAVIS_PR_REPOSITORY_SLUG = "TRAVIS_PULL_REQUEST_SLUG";
  public static final String TRAVIS_GIT_COMMIT = "TRAVIS_COMMIT";
  public static final String TRAVIS_GIT_PR_BRANCH = "TRAVIS_PULL_REQUEST_BRANCH";
  public static final String TRAVIS_GIT_BRANCH = "TRAVIS_BRANCH";
  public static final String TRAVIS_GIT_TAG = "TRAVIS_TAG";
  public static final String TRAVIS_GIT_COMMIT_MESSAGE = "TRAVIS_COMMIT_MESSAGE";
  public static final String TRAVIS_PULL_REQUEST = "TRAVIS_PULL_REQUEST";
  public static final String TRAVIS_PULL_REQUEST_HEAD_SHA = "TRAVIS_PULL_REQUEST_SHA";

  private final CiEnvironment environment;

  TravisInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        buildGitRepositoryUrl(),
        buildGitBranch(),
        normalizeTag(environment.get(TRAVIS_GIT_TAG)),
        new CommitInfo(
            environment.get(TRAVIS_GIT_COMMIT),
            PersonInfo.NOOP,
            PersonInfo.NOOP,
            environment.get(TRAVIS_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(TRAVIS_PROVIDER_NAME)
        .ciPipelineId(environment.get(TRAVIS_PIPELINE_ID))
        .ciPipelineName(buildCiPipelineName())
        .ciPipelineNumber(environment.get(TRAVIS_PIPELINE_NUMBER))
        .ciPipelineUrl(environment.get(TRAVIS_PIPELINE_URL))
        .ciJobUrl(environment.get(TRAVIS_JOB_URL))
        .ciWorkspace(expandTilde(environment.get(TRAVIS_WORKSPACE_PATH)))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    if (isPullRequest()) {
      return new PullRequestInfo(
          normalizeBranch(environment.get(TRAVIS_GIT_BRANCH)),
          null,
          environment.get(TRAVIS_PULL_REQUEST_HEAD_SHA));
    }
    return PullRequestInfo.EMPTY;
  }

  private boolean isPullRequest() {
    String pullRequest = environment.get(TRAVIS_PULL_REQUEST);
    return pullRequest != null && !"false".equals(pullRequest);
  }

  private String buildGitBranch() {
    final String fromBranch = environment.get(TRAVIS_GIT_PR_BRANCH);
    if (fromBranch != null && !fromBranch.isEmpty()) {
      return normalizeBranch(fromBranch);
    } else {
      return normalizeBranch(environment.get(TRAVIS_GIT_BRANCH));
    }
  }

  private String buildGitRepositoryUrl() {
    String repoSlug = environment.get(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = environment.get(TRAVIS_REPOSITORY_SLUG);
    }

    if (repoSlug == null || repoSlug.isEmpty()) {
      return null;
    }
    return String.format("https://github.com/%s.git", repoSlug);
  }

  private String buildCiPipelineName() {
    String repoSlug = environment.get(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = environment.get(TRAVIS_REPOSITORY_SLUG);
    }
    return repoSlug;
  }

  @Override
  public Provider getProvider() {
    return Provider.TRAVISCI;
  }
}

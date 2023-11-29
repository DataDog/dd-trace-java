package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;

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

  @Override
  public GitInfo buildCIGitInfo() {

    return new GitInfo(
        buildGitRepositoryUrl(),
        buildGitBranch(),
        normalizeTag(System.getenv(TRAVIS_GIT_TAG)),
        new CommitInfo(
            System.getenv(TRAVIS_GIT_COMMIT),
            PersonInfo.NOOP,
            PersonInfo.NOOP,
            System.getenv(TRAVIS_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(TRAVIS_PROVIDER_NAME)
        .ciPipelineId(System.getenv(TRAVIS_PIPELINE_ID))
        .ciPipelineName(buildCiPipelineName())
        .ciPipelineNumber(System.getenv(TRAVIS_PIPELINE_NUMBER))
        .ciPipelineUrl(System.getenv(TRAVIS_PIPELINE_URL))
        .ciJobUrl(System.getenv(TRAVIS_JOB_URL))
        .ciWorkspace(expandTilde(System.getenv(TRAVIS_WORKSPACE_PATH)))
        .build();
  }

  private String buildGitBranch() {
    final String fromBranch = System.getenv(TRAVIS_GIT_PR_BRANCH);
    if (fromBranch != null && !fromBranch.isEmpty()) {
      return normalizeBranch(fromBranch);
    } else {
      return normalizeBranch(System.getenv(TRAVIS_GIT_BRANCH));
    }
  }

  private String buildGitRepositoryUrl() {
    String repoSlug = System.getenv(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = System.getenv(TRAVIS_REPOSITORY_SLUG);
    }

    if (repoSlug == null || repoSlug.isEmpty()) {
      return null;
    }
    return String.format("https://github.com/%s.git", repoSlug);
  }

  private String buildCiPipelineName() {
    String repoSlug = System.getenv(TRAVIS_PR_REPOSITORY_SLUG);
    if (repoSlug == null || repoSlug.isEmpty()) {
      repoSlug = System.getenv(TRAVIS_REPOSITORY_SLUG);
    }
    return repoSlug;
  }
}

package datadog.trace.bootstrap.instrumentation.ci;

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

  CircleCIInfo() {
    final String gitTag = normalizeRef(System.getenv(CIRCLECI_GIT_TAG));
    final String commit = System.getenv(CIRCLECI_GIT_COMMIT);

    this.ciTags =
        new CITagsBuilder()
            .withCiProviderName(CIRCLECI_PROVIDER_NAME)
            .withCiPipelineId(System.getenv(CIRCLECI_PIPELINE_ID))
            .withCiPipelineName(System.getenv(CIRCLECI_PIPELINE_NAME))
            .withCiPipelineNumber(System.getenv(CIRCLECI_PIPELINE_NUMBER))
            .withCiPipelineUrl(System.getenv(CIRCLECI_BUILD_URL))
            .withCiJorUrl(System.getenv(CIRCLECI_BUILD_URL))
            .withCiWorkspacePath(getWorkspace())
            .withGitRepositoryUrl(
                filterSensitiveInfo(System.getenv(CIRCLECI_GIT_REPOSITORY_URL)),
                getLocalGitRepositoryUrl())
            .withGitCommit(System.getenv(CIRCLECI_GIT_COMMIT), getLocalGitCommitSha())
            .withGitBranch(buildGitBranch(gitTag), getLocalGitBranch())
            .withGitTag(gitTag, getLocalGitTag())
            .withGitCommitAuthorName(commit, getLocalGitCommitSha(), getLocalGitCommitAuthorName())
            .withGitCommitAuthorEmail(
                commit, getLocalGitCommitSha(), getLocalGitCommitAuthorEmail())
            .withGitCommitAuthorDate(commit, getLocalGitCommitSha(), getLocalGitCommitAuthorDate())
            .withGitCommitCommitterName(
                commit, getLocalGitCommitSha(), getLocalGitCommitCommitterName())
            .withGitCommitCommitterEmail(
                commit, getLocalGitCommitSha(), getLocalGitCommitCommitterEmail())
            .withGitCommitCommitterDate(
                commit, getLocalGitCommitSha(), getLocalGitCommitCommitterDate())
            .withGitCommitMessage(commit, getLocalGitCommitSha(), getLocalGitCommitMessage())
            .build();
  }

  @Override
  protected String buildWorkspace() {
    return System.getenv(CIRCLECI_WORKSPACE_PATH);
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    return normalizeRef(System.getenv(CIRCLECI_GIT_BRANCH));
  }
}

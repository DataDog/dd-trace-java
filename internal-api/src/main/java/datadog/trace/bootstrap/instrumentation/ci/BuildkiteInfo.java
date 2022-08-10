package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.PersonInfo;

class BuildkiteInfo extends CIProviderInfo {

  // https://buildkite.com/docs/pipelines/environment-variables
  public static final String BUILDKITE = "BUILDKITE";
  public static final String BUILDKITE_PROVIDER_NAME = "buildkite";
  public static final String BUILDKITE_PIPELINE_ID = "BUILDKITE_BUILD_ID";
  public static final String BUILDKITE_PIPELINE_NAME = "BUILDKITE_PIPELINE_SLUG";
  public static final String BUILDKITE_PIPELINE_NUMBER = "BUILDKITE_BUILD_NUMBER";
  public static final String BUILDKITE_BUILD_URL = "BUILDKITE_BUILD_URL";
  public static final String BUILDKITE_JOB_ID = "BUILDKITE_JOB_ID";
  public static final String BUILDKITE_WORKSPACE_PATH = "BUILDKITE_BUILD_CHECKOUT_PATH";
  public static final String BUILDKITE_GIT_REPOSITORY_URL = "BUILDKITE_REPO";
  public static final String BUILDKITE_GIT_COMMIT = "BUILDKITE_COMMIT";
  public static final String BUILDKITE_GIT_BRANCH = "BUILDKITE_BRANCH";
  public static final String BUILDKITE_GIT_TAG = "BUILDKITE_TAG";
  public static final String BUILDKITE_GIT_MESSAGE = "BUILDKITE_MESSAGE";
  public static final String BUILDKITE_GIT_AUTHOR_NAME = "BUILDKITE_BUILD_AUTHOR";
  public static final String BUILDKITE_GIT_AUTHOR_EMAIL = "BUILDKITE_BUILD_AUTHOR_EMAIL";

  @Override
  protected GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(System.getenv(BUILDKITE_GIT_REPOSITORY_URL)),
        normalizeRef(System.getenv(BUILDKITE_GIT_BRANCH)),
        normalizeRef(System.getenv(BUILDKITE_GIT_TAG)),
        new CommitInfo(
            System.getenv(BUILDKITE_GIT_COMMIT),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            System.getenv(BUILDKITE_GIT_MESSAGE)));
  }

  @Override
  protected CIInfo buildCIInfo() {
    final String ciPipelineUrl = System.getenv(BUILDKITE_BUILD_URL);

    return CIInfo.builder()
        .ciProviderName(BUILDKITE_PROVIDER_NAME)
        .ciPipelineId(System.getenv(BUILDKITE_PIPELINE_ID))
        .ciPipelineName(System.getenv(BUILDKITE_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(BUILDKITE_PIPELINE_NUMBER))
        .ciPipelineUrl(ciPipelineUrl)
        .ciJobUrl(String.format("%s#%s", ciPipelineUrl, System.getenv(BUILDKITE_JOB_ID)))
        .ciWorkspace(expandTilde(System.getenv(BUILDKITE_WORKSPACE_PATH)))
        .ciEnvVars(BUILDKITE_PIPELINE_ID, BUILDKITE_JOB_ID)
        .build();
  }

  private PersonInfo buildGitCommitAuthor() {
    return new PersonInfo(
        System.getenv(BUILDKITE_GIT_AUTHOR_NAME), System.getenv(BUILDKITE_GIT_AUTHOR_EMAIL));
  }
}

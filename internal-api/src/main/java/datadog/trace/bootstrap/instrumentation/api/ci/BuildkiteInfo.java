package datadog.trace.bootstrap.instrumentation.api.ci;

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

  BuildkiteInfo() {
    final String ciPipelineUrl = System.getenv(BUILDKITE_BUILD_URL);

    this.ciTags =
        new CITagsBuilder()
            .withCiProviderName(BUILDKITE_PROVIDER_NAME)
            .withCiPipelineId(System.getenv(BUILDKITE_PIPELINE_ID))
            .withCiPipelineName(System.getenv(BUILDKITE_PIPELINE_NAME))
            .withCiPipelineNumber(System.getenv(BUILDKITE_PIPELINE_NUMBER))
            .withCiPipelineUrl(ciPipelineUrl)
            .withCiJorUrl(String.format("%s#%s", ciPipelineUrl, System.getenv(BUILDKITE_JOB_ID)))
            .withCiWorkspacePath(expandTilde(System.getenv(BUILDKITE_WORKSPACE_PATH)))
            .withGitRepositoryUrl(filterSensitiveInfo(System.getenv(BUILDKITE_GIT_REPOSITORY_URL)))
            .withGitCommit(System.getenv(BUILDKITE_GIT_COMMIT))
            .withGitBranch(normalizeRef(System.getenv(BUILDKITE_GIT_BRANCH)))
            .withGitTag(normalizeRef(System.getenv(BUILDKITE_GIT_TAG)))
            .build();
  }
}

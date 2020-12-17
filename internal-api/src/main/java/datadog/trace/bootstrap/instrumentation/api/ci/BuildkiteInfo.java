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
    ciProviderName = BUILDKITE_PROVIDER_NAME;
    ciPipelineId = System.getenv(BUILDKITE_PIPELINE_ID);
    ciPipelineName = System.getenv(BUILDKITE_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(BUILDKITE_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(BUILDKITE_BUILD_URL);
    ciJobUrl = String.format("%s#%s", ciPipelineUrl, System.getenv(BUILDKITE_JOB_ID));
    ciWorkspacePath = expandTilde(System.getenv(BUILDKITE_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(BUILDKITE_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(BUILDKITE_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(BUILDKITE_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(BUILDKITE_GIT_TAG));

    updateCiTags();
  }
}

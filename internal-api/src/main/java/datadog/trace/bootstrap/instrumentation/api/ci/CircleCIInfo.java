package datadog.trace.bootstrap.instrumentation.api.ci;

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

  private final String ciProviderName;
  private final String ciPipelineId;
  private final String ciPipelineName;
  private final String ciPipelineNumber;
  private final String ciPipelineUrl;
  private final String ciJobUrl;
  private final String ciWorkspacePath;
  private final String gitRepositoryUrl;
  private final String gitCommit;
  private final String gitBranch;
  private final String gitTag;

  CircleCIInfo() {
    ciProviderName = CIRCLECI_PROVIDER_NAME;
    ciPipelineId = System.getenv(CIRCLECI_PIPELINE_ID);
    ciPipelineName = System.getenv(CIRCLECI_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(CIRCLECI_PIPELINE_NUMBER);
    ciPipelineUrl = System.getenv(CIRCLECI_BUILD_URL);
    ciJobUrl = System.getenv(CIRCLECI_BUILD_URL);
    ciWorkspacePath = expandTilde(System.getenv(CIRCLECI_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(CIRCLECI_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(CIRCLECI_GIT_COMMIT);
    gitTag = normalizeRef(System.getenv(CIRCLECI_GIT_TAG));
    gitBranch = buildGitBranch(gitTag);
  }

  private String buildGitBranch(final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    return normalizeRef(System.getenv(CIRCLECI_GIT_BRANCH));
  }

  @Override
  public String getCiProviderName() {
    return this.ciProviderName;
  }

  @Override
  public String getCiPipelineId() {
    return this.ciPipelineId;
  }

  @Override
  public String getCiPipelineName() {
    return this.ciPipelineName;
  }

  @Override
  public String getCiPipelineNumber() {
    return this.ciPipelineNumber;
  }

  @Override
  public String getCiPipelineUrl() {
    return this.ciPipelineUrl;
  }

  @Override
  public String getCiJobUrl() {
    return this.ciJobUrl;
  }

  @Override
  public String getCiWorkspacePath() {
    return this.ciWorkspacePath;
  }

  @Override
  public String getGitRepositoryUrl() {
    return this.gitRepositoryUrl;
  }

  @Override
  public String getGitCommit() {
    return this.gitCommit;
  }

  @Override
  public String getGitBranch() {
    return this.gitBranch;
  }

  @Override
  public String getGitTag() {
    return this.gitTag;
  }
}

package datadog.trace.bootstrap.instrumentation.api.ci;

class GitLabInfo extends CIProviderInfo {

  // https://docs.gitlab.com/ee/ci/variables/predefined_variables.html
  public static final String GITLAB = "GITLAB_CI";
  public static final String GITLAB_PROVIDER_NAME = "gitlab";
  public static final String GITLAB_PIPELINE_ID = "CI_PIPELINE_ID";
  public static final String GITLAB_PIPELINE_NAME = "CI_PROJECT_PATH";
  public static final String GITLAB_PIPELINE_NUMBER = "CI_PIPELINE_IID";
  public static final String GITLAB_PIPELINE_URL = "CI_PIPELINE_URL";
  public static final String GITLAB_JOB_URL = "CI_JOB_URL";
  public static final String GITLAB_WORKSPACE_PATH = "CI_PROJECT_DIR";
  public static final String GITLAB_GIT_REPOSITORY_URL = "CI_REPOSITORY_URL";
  public static final String GITLAB_GIT_COMMIT = "CI_COMMIT_SHA";
  public static final String GITLAB_GIT_BRANCH = "CI_COMMIT_BRANCH";
  public static final String GITLAB_GIT_TAG = "CI_COMMIT_TAG";

  GitLabInfo() {
    ciProviderName = GITLAB_PROVIDER_NAME;
    ciPipelineId = System.getenv(GITLAB_PIPELINE_ID);
    ciPipelineName = System.getenv(GITLAB_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(GITLAB_PIPELINE_NUMBER);
    ciPipelineUrl = buildPipelineUrl();
    ciJobUrl = System.getenv(GITLAB_JOB_URL);
    ciWorkspacePath = expandTilde(System.getenv(GITLAB_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(GITLAB_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(GITLAB_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(GITLAB_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(GITLAB_GIT_TAG));

    updateCiTags();
  }

  private String buildPipelineUrl() {
    final String pipelineUrl = System.getenv(GITLAB_PIPELINE_URL);
    if (pipelineUrl == null || pipelineUrl.isEmpty()) {
      return null;
    }

    return pipelineUrl.replace("/-/pipelines/", "/pipelines/");
  }
}

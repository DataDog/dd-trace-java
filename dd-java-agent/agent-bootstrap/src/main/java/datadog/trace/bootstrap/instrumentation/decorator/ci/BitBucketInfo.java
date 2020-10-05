package datadog.trace.bootstrap.instrumentation.decorator.ci;

class BitBucketInfo extends CIProviderInfo {

  // https://support.atlassian.com/bitbucket-cloud/docs/variables-and-secrets/
  public static final String BITBUCKET = "BITBUCKET_COMMIT";
  public static final String BITBUCKET_PROVIDER_NAME = "bitbucket";
  public static final String BITBUCKET_PIPELINE_ID = "BITBUCKET_PIPELINE_UUID";
  public static final String BITBUCKET_REPO_FULL_NAME = "BITBUCKET_REPO_FULL_NAME";
  public static final String BITBUCKET_BUILD_NUMBER = "BITBUCKET_BUILD_NUMBER";
  public static final String BITBUCKET_WORKSPACE_PATH = "BITBUCKET_CLONE_DIR";
  public static final String BITBUCKET_GIT_REPOSITORY_URL = "BITBUCKET_GIT_SSH_ORIGIN";
  public static final String BITBUCKET_GIT_COMMIT = "BITBUCKET_COMMIT";
  public static final String BITBUCKET_GIT_BRANCH = "BITBUCKET_BRANCH";
  public static final String BITBUCKET_GIT_TAG = "BITBUCKET_TAG";

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

  BitBucketInfo() {
    final String repo = System.getenv(BITBUCKET_REPO_FULL_NAME);
    final String number = System.getenv(BITBUCKET_BUILD_NUMBER);
    final String url = buildPipelineUrl(repo, number);

    ciProviderName = BITBUCKET_PROVIDER_NAME;
    ciPipelineId = buildPipelineId();
    ciPipelineName = repo;
    ciPipelineNumber = number;
    ciPipelineUrl = url;
    ciJobUrl = url;
    ciWorkspacePath = expandTilde(System.getenv(BITBUCKET_WORKSPACE_PATH));
    gitRepositoryUrl = filterSensitiveInfo(System.getenv(BITBUCKET_GIT_REPOSITORY_URL));
    gitCommit = System.getenv(BITBUCKET_GIT_COMMIT);
    gitBranch = normalizeRef(System.getenv(BITBUCKET_GIT_BRANCH));
    gitTag = normalizeRef(System.getenv(BITBUCKET_GIT_TAG));
  }

  private String buildPipelineUrl(final String repo, final String number) {
    return String.format(
        "https://bitbucket.org/%s/addon/pipelines/home#!/results/%s", repo, number);
  }

  private String buildPipelineId() {
    String id = System.getenv(BITBUCKET_PIPELINE_ID);
    if (id != null) {
      id = id.replaceAll("}", "").replaceAll("\\{", "");
    }
    return id;
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

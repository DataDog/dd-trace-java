package datadog.trace.api.ci;

class AppVeyorInfo extends CIProviderInfo {

  // https://www.appveyor.com/docs/environment-variables/
  public static final String APPVEYOR = "APPVEYOR";
  public static final String APPVEYOR_PROVIDER_NAME = "appveyor";
  public static final String APPVEYOR_BUILD_ID = "APPVEYOR_BUILD_ID";
  public static final String APPVEYOR_REPO_NAME = "APPVEYOR_REPO_NAME";
  public static final String APPVEYOR_PIPELINE_NUMBER = "APPVEYOR_BUILD_NUMBER";
  public static final String APPVEYOR_WORKSPACE_PATH = "APPVEYOR_BUILD_FOLDER";
  public static final String APPVEYOR_REPO_PROVIDER = "APPVEYOR_REPO_PROVIDER";
  public static final String APPVEYOR_REPO_COMMIT = "APPVEYOR_REPO_COMMIT";
  public static final String APPVEYOR_REPO_BRANCH = "APPVEYOR_REPO_BRANCH";
  public static final String APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH =
      "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH";
  public static final String APPVEYOR_REPO_TAG_NAME = "APPVEYOR_REPO_TAG_NAME";

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

  AppVeyorInfo() {
    final String buildId = System.getenv(APPVEYOR_BUILD_ID);
    final String repoName = System.getenv(APPVEYOR_REPO_NAME);
    final String url = buildPipelineUrl(repoName, buildId);
    final String repoProvider = System.getenv(APPVEYOR_REPO_PROVIDER);

    ciProviderName = APPVEYOR_PROVIDER_NAME;
    ciPipelineId = buildId;
    ciPipelineName = repoName;
    ciPipelineNumber = System.getenv(APPVEYOR_PIPELINE_NUMBER);
    ciPipelineUrl = url;
    ciJobUrl = url;
    ciWorkspacePath = expandTilde(System.getenv(APPVEYOR_WORKSPACE_PATH));
    gitRepositoryUrl = buildGitRepositoryUrl(repoProvider, repoName);
    gitCommit = buildGitCommit(repoProvider);
    gitTag = buildGitTag(repoProvider);
    gitBranch = buildGitBranch(repoProvider, gitTag);
  }

  private String buildGitTag(final String repoProvider) {
    if ("github".equals(repoProvider)) {
      return normalizeRef(System.getenv(APPVEYOR_REPO_TAG_NAME));
    }
    return null;
  }

  private String buildGitBranch(final String repoProvider, final String gitTag) {
    if (gitTag != null) {
      return null;
    }

    if ("github".equals(repoProvider)) {
      String branch = System.getenv(APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH);
      if (branch == null || branch.isEmpty()) {
        branch = System.getenv(APPVEYOR_REPO_BRANCH);
      }
      return normalizeRef(branch);
    }
    return null;
  }

  private String buildGitCommit(final String repoProvider) {
    if ("github".equals(repoProvider)) {
      return System.getenv(APPVEYOR_REPO_COMMIT);
    }
    return null;
  }

  private String buildGitRepositoryUrl(final String repoProvider, final String repoName) {
    if ("github".equals(repoProvider)) {
      return String.format("https://github.com/%s.git", repoName);
    }
    return null;
  }

  private String buildPipelineUrl(final String repoName, final String buildId) {
    return String.format("https://ci.appveyor.com/project/%s/builds/%s", repoName, buildId);
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

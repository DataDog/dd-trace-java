package datadog.trace.bootstrap.instrumentation.api.ci;

class GithubActionsInfo extends CIProviderInfo {

  // https://docs.github.com/en/free-pro-team@latest/actions/reference/environment-variables#default-environment-variables
  public static final String GHACTIONS = "GITHUB_ACTION";
  public static final String GHACTIONS_PROVIDER_NAME = "github";
  public static final String GHACTIONS_PIPELINE_ID = "GITHUB_RUN_ID";
  public static final String GHACTIONS_PIPELINE_NAME = "GITHUB_WORKFLOW";
  public static final String GHACTIONS_PIPELINE_NUMBER = "GITHUB_RUN_NUMBER";
  public static final String GHACTIONS_WORKSPACE_PATH = "GITHUB_WORKSPACE";
  public static final String GHACTIONS_REPOSITORY = "GITHUB_REPOSITORY";
  public static final String GHACTIONS_SHA = "GITHUB_SHA";
  public static final String GHACTIONS_HEAD_REF = "GITHUB_HEAD_REF";
  public static final String GHACTIONS_REF = "GITHUB_REF";

  GithubActionsInfo() {
    final String repo = System.getenv(GHACTIONS_REPOSITORY);
    final String commit = System.getenv(GHACTIONS_SHA);
    final String url = buildPipelineUrl(repo, commit);

    ciProviderName = GHACTIONS_PROVIDER_NAME;
    ciPipelineId = System.getenv(GHACTIONS_PIPELINE_ID);
    ciPipelineName = System.getenv(GHACTIONS_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(GHACTIONS_PIPELINE_NUMBER);
    ciPipelineUrl = url;
    ciJobUrl = url;
    ciWorkspacePath = expandTilde(System.getenv(GHACTIONS_WORKSPACE_PATH));
    gitRepositoryUrl = buildGitRepositoryUrl(repo);
    gitCommit = commit;
    gitBranch = buildGitBranch();
    gitTag = buildGitTag();

    updateCiTags();
  }

  private String buildGitTag() {
    String gitBranchOrTag = System.getenv(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(GHACTIONS_REF);
    }

    if (gitBranchOrTag != null && gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitBranch() {
    String gitBranchOrTag = System.getenv(GHACTIONS_HEAD_REF);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(GHACTIONS_REF);
    }

    if (gitBranchOrTag != null && !gitBranchOrTag.contains("tags")) {
      return normalizeRef(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitRepositoryUrl(final String repo) {
    return String.format("https://github.com/%s.git", repo);
  }

  private String buildPipelineUrl(final String repo, final String commit) {
    return String.format("https://github.com/%s/commit/%s/checks", repo, commit);
  }
}

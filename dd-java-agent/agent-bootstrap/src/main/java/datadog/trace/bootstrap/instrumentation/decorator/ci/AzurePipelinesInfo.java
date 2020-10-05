package datadog.trace.bootstrap.instrumentation.decorator.ci;

class AzurePipelinesInfo extends CIProviderInfo {

  // https://docs.microsoft.com/en-us/azure/devops/pipelines/build/variables?view=azure-devops
  public static final String AZURE = "TF_BUILD";
  public static final String AZURE_PROVIDER_NAME = "azurepipelines";
  public static final String AZURE_PIPELINE_NAME = "BUILD_DEFINITIONNAME";
  public static final String AZURE_PIPELINE_NUMBER = "BUILD_BUILDNUMBER";
  public static final String AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI =
      "SYSTEM_TEAMFOUNDATIONSERVERURI";
  public static final String AZURE_SYSTEM_TEAMPROJECT = "SYSTEM_TEAMPROJECT";
  public static final String AZURE_BUILD_BUILDID = "BUILD_BUILDID";
  public static final String AZURE_SYSTEM_JOBID = "SYSTEM_JOBID";
  public static final String AZURE_SYSTEM_TASKINSTANCEID = "SYSTEM_TASKINSTANCEID";
  public static final String AZURE_WORKSPACE_PATH = "BUILD_SOURCESDIRECTORY";
  public static final String AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI =
      "SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI";
  public static final String AZURE_BUILD_REPOSITORY_URI = "BUILD_REPOSITORY_URI";
  public static final String AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID =
      "SYSTEM_PULLREQUEST_SOURCECOMMITID";
  public static final String AZURE_BUILD_SOURCEVERSION = "BUILD_SOURCEVERSION";
  public static final String AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH =
      "SYSTEM_PULLREQUEST_SOURCEBRANCH";
  public static final String AZURE_BUILD_SOURCEBRANCH = "BUILD_SOURCEBRANCH";

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

  AzurePipelinesInfo() {
    final String uri = System.getenv(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI);
    final String project = System.getenv(AZURE_SYSTEM_TEAMPROJECT);
    final String buildId = System.getenv(AZURE_BUILD_BUILDID);
    final String jobId = System.getenv(AZURE_SYSTEM_JOBID);
    final String taskId = System.getenv(AZURE_SYSTEM_TASKINSTANCEID);

    ciProviderName = AZURE_PROVIDER_NAME;
    ciPipelineId = System.getenv(AZURE_BUILD_BUILDID);
    ciPipelineName = System.getenv(AZURE_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(AZURE_PIPELINE_NUMBER);
    ciWorkspacePath = expandTilde(System.getenv(AZURE_WORKSPACE_PATH));
    ciPipelineUrl = buildCiPipelineUrl(uri, project, buildId);
    ciJobUrl = buildCiJobUrl(uri, project, buildId, jobId, taskId);
    gitRepositoryUrl = buildGitRepositoryUrl();
    gitCommit = buildGitCommit();
    gitBranch = buildGitBranch();
    gitTag = buildGitTag();
  }

  private String buildGitTag() {
    String branchOrTag = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH);
    if (branchOrTag == null || branchOrTag.isEmpty()) {
      branchOrTag = System.getenv(AZURE_BUILD_SOURCEBRANCH);
    }
    if (branchOrTag != null && branchOrTag.contains("tags")) {
      return normalizeRef(branchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitBranch() {
    String branchOrTag = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH);
    if (branchOrTag == null || branchOrTag.isEmpty()) {
      branchOrTag = System.getenv(AZURE_BUILD_SOURCEBRANCH);
    }
    if (branchOrTag != null && !branchOrTag.contains("tags")) {
      return normalizeRef(branchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitCommit() {
    String commit = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID);
    if (commit == null || commit.isEmpty()) {
      commit = System.getenv(AZURE_BUILD_SOURCEVERSION);
    }
    return commit;
  }

  private String buildGitRepositoryUrl() {
    String repoUrl = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI);
    if (repoUrl == null || repoUrl.isEmpty()) {
      repoUrl = System.getenv(AZURE_BUILD_REPOSITORY_URI);
    }
    return filterSensitiveInfo(repoUrl);
  }

  private String buildCiJobUrl(
      final String uri,
      final String project,
      final String buildId,
      final String jobId,
      final String taskId) {
    return String.format(
        "%s%s/_build/results?buildId=%s&view=logs&j=%s&t=%s", uri, project, buildId, jobId, taskId);
  }

  private String buildCiPipelineUrl(final String uri, final String project, final String buildId) {
    return String.format("%s%s/_build/results?buildId=%s&_a=summary", uri, project, buildId);
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

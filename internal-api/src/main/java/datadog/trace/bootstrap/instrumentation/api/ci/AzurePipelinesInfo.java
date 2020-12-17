package datadog.trace.bootstrap.instrumentation.api.ci;

class AzurePipelinesInfo extends CIProviderInfo {

  // https://docs.microsoft.com/en-us/azure/devops/pipelines/build/variables?view=azure-devops
  public static final String AZURE = "TF_BUILD";
  public static final String AZURE_PROVIDER_NAME = "azurepipelines";
  public static final String AZURE_PIPELINE_NAME = "BUILD_DEFINITIONNAME";
  public static final String AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI =
      "SYSTEM_TEAMFOUNDATIONSERVERURI";
  public static final String AZURE_SYSTEM_TEAMPROJECTID = "SYSTEM_TEAMPROJECTID";
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

  AzurePipelinesInfo() {
    final String uri = System.getenv(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI);
    final String project = System.getenv(AZURE_SYSTEM_TEAMPROJECTID);
    final String buildId = System.getenv(AZURE_BUILD_BUILDID);
    final String jobId = System.getenv(AZURE_SYSTEM_JOBID);
    final String taskId = System.getenv(AZURE_SYSTEM_TASKINSTANCEID);

    ciProviderName = AZURE_PROVIDER_NAME;
    ciPipelineId = System.getenv(AZURE_BUILD_BUILDID);
    ciPipelineName = System.getenv(AZURE_PIPELINE_NAME);
    ciPipelineNumber = System.getenv(AZURE_BUILD_BUILDID);
    ciWorkspacePath = expandTilde(System.getenv(AZURE_WORKSPACE_PATH));
    ciPipelineUrl = buildCiPipelineUrl(uri, project, buildId);
    ciJobUrl = buildCiJobUrl(uri, project, buildId, jobId, taskId);
    gitRepositoryUrl = buildGitRepositoryUrl();
    gitCommit = buildGitCommit();
    gitBranch = buildGitBranch();
    gitTag = buildGitTag();

    updateCiTags();
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
}

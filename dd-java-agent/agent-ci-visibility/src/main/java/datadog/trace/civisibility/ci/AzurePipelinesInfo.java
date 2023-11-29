package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;

class AzurePipelinesInfo implements CIProviderInfo {

  // https://docs.microsoft.com/en-us/azure/devops/pipelines/build/variables?view=azure-devops
  public static final String AZURE = "TF_BUILD";
  public static final String AZURE_PROVIDER_NAME = "azurepipelines";
  public static final String AZURE_PIPELINE_NAME = "BUILD_DEFINITIONNAME";
  public static final String AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI =
      "SYSTEM_TEAMFOUNDATIONSERVERURI";
  public static final String AZURE_SYSTEM_TEAMPROJECTID = "SYSTEM_TEAMPROJECTID";
  public static final String AZURE_SYSTEM_STAGEDISPLAYNAME = "SYSTEM_STAGEDISPLAYNAME";
  public static final String AZURE_SYSTEM_JOBDISPLAYNAME = "SYSTEM_JOBDISPLAYNAME";
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
  public static final String AZURE_BUILD_SOURCEVERSION_MESSAGE = "BUILD_SOURCEVERSIONMESSAGE";
  public static final String AZURE_BUILD_REQUESTED_FOR_ID = "BUILD_REQUESTEDFORID";
  public static final String AZURE_BUILD_REQUESTED_FOR_EMAIL = "BUILD_REQUESTEDFOREMAIL";

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        buildGitRepositoryUrl(),
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(
            buildGitCommit(),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            System.getenv(AZURE_BUILD_SOURCEVERSION_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String uri = System.getenv(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI);
    final String project = System.getenv(AZURE_SYSTEM_TEAMPROJECTID);
    final String buildId = System.getenv(AZURE_BUILD_BUILDID);
    final String jobId = System.getenv(AZURE_SYSTEM_JOBID);
    final String taskId = System.getenv(AZURE_SYSTEM_TASKINSTANCEID);

    return CIInfo.builder()
        .ciProviderName(AZURE_PROVIDER_NAME)
        .ciPipelineId(System.getenv(AZURE_BUILD_BUILDID))
        .ciPipelineName(System.getenv(AZURE_PIPELINE_NAME))
        .ciPipelineNumber(buildId)
        .ciPipelineUrl(buildCiPipelineUrl(uri, project, buildId))
        .ciStageName(System.getenv(AZURE_SYSTEM_STAGEDISPLAYNAME))
        .ciJobName(System.getenv(AZURE_SYSTEM_JOBDISPLAYNAME))
        .ciJobUrl(buildCiJobUrl(uri, project, buildId, jobId, taskId))
        .ciWorkspace(expandTilde(System.getenv(AZURE_WORKSPACE_PATH)))
        .ciEnvVars(AZURE_SYSTEM_TEAMPROJECTID, AZURE_BUILD_BUILDID, AZURE_SYSTEM_JOBID)
        .build();
  }

  private String buildGitBranch() {
    String gitBranchOrTag = getGitBranchOrTag();
    if (!isTagReference(gitBranchOrTag)) {
      return normalizeBranch(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitTag() {
    String gitBranchOrTag = getGitBranchOrTag();
    if (isTagReference(gitBranchOrTag)) {
      return normalizeTag(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private static String getGitBranchOrTag() {
    String gitBranchOrTag = System.getenv(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = System.getenv(AZURE_BUILD_SOURCEBRANCH);
    }
    return gitBranchOrTag;
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
    return String.format("%s%s/_build/results?buildId=%s", uri, project, buildId);
  }

  private PersonInfo buildGitCommitAuthor() {
    return new PersonInfo(
        System.getenv(AZURE_BUILD_REQUESTED_FOR_ID),
        System.getenv(AZURE_BUILD_REQUESTED_FOR_EMAIL));
  }
}

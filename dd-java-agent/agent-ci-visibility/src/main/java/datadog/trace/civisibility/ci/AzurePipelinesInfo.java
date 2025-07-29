package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

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
  public static final String AZURE_PR_NUMBER = "SYSTEM_PULLREQUEST_PULLREQUESTNUMBER";
  public static final String AZURE_PR_TARGET_BRANCH = "SYSTEM_PULLREQUEST_TARGETBRANCH";

  private final CiEnvironment environment;

  AzurePipelinesInfo(CiEnvironment environment) {
    this.environment = environment;
  }

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
            environment.get(AZURE_BUILD_SOURCEVERSION_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String uri = environment.get(AZURE_SYSTEM_TEAMFOUNDATIONSERVERURI);
    final String project = environment.get(AZURE_SYSTEM_TEAMPROJECTID);
    final String buildId = environment.get(AZURE_BUILD_BUILDID);
    final String jobId = environment.get(AZURE_SYSTEM_JOBID);
    final String taskId = environment.get(AZURE_SYSTEM_TASKINSTANCEID);

    return CIInfo.builder(environment)
        .ciProviderName(AZURE_PROVIDER_NAME)
        .ciPipelineId(environment.get(AZURE_BUILD_BUILDID))
        .ciPipelineName(environment.get(AZURE_PIPELINE_NAME))
        .ciPipelineNumber(buildId)
        .ciPipelineUrl(buildCiPipelineUrl(uri, project, buildId))
        .ciStageName(environment.get(AZURE_SYSTEM_STAGEDISPLAYNAME))
        .ciJobId(jobId)
        .ciJobName(environment.get(AZURE_SYSTEM_JOBDISPLAYNAME))
        .ciJobUrl(buildCiJobUrl(uri, project, buildId, jobId, taskId))
        .ciWorkspace(expandTilde(environment.get(AZURE_WORKSPACE_PATH)))
        .ciEnvVars(AZURE_SYSTEM_TEAMPROJECTID, AZURE_BUILD_BUILDID, AZURE_SYSTEM_JOBID)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(AZURE_PR_TARGET_BRANCH)),
        null,
        CommitInfo.NOOP,
        environment.get(AZURE_PR_NUMBER));
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

  private String getGitBranchOrTag() {
    String gitBranchOrTag = environment.get(AZURE_SYSTEM_PULLREQUEST_SOURCEBRANCH);
    if (gitBranchOrTag == null || gitBranchOrTag.isEmpty()) {
      gitBranchOrTag = environment.get(AZURE_BUILD_SOURCEBRANCH);
    }
    return gitBranchOrTag;
  }

  private String buildGitCommit() {
    String commit = environment.get(AZURE_SYSTEM_PULLREQUEST_SOURCECOMMITID);
    if (commit == null || commit.isEmpty()) {
      commit = environment.get(AZURE_BUILD_SOURCEVERSION);
    }
    return commit;
  }

  private String buildGitRepositoryUrl() {
    String repoUrl = environment.get(AZURE_SYSTEM_PULLREQUEST_SOURCEREPOSITORYURI);
    if (repoUrl == null || repoUrl.isEmpty()) {
      repoUrl = environment.get(AZURE_BUILD_REPOSITORY_URI);
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
        environment.get(AZURE_BUILD_REQUESTED_FOR_ID),
        environment.get(AZURE_BUILD_REQUESTED_FOR_EMAIL));
  }

  @Override
  public Provider getProvider() {
    return Provider.AZP;
  }
}

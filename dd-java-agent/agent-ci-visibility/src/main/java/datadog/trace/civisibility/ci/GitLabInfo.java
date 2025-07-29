package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitUtils;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import javax.annotation.Nonnull;

@SuppressForbidden
class GitLabInfo implements CIProviderInfo {

  // https://docs.gitlab.com/ee/ci/variables/predefined_variables.html
  public static final String GITLAB = "GITLAB_CI";
  public static final String GITLAB_PROVIDER_NAME = "gitlab";
  public static final String GITLAB_PROJECT_URL = "CI_PROJECT_URL";
  public static final String GITLAB_PIPELINE_ID = "CI_PIPELINE_ID";
  public static final String GITLAB_PIPELINE_NAME = "CI_PROJECT_PATH";
  public static final String GITLAB_PIPELINE_NUMBER = "CI_PIPELINE_IID";
  public static final String GITLAB_PIPELINE_URL = "CI_PIPELINE_URL";
  public static final String GITLAB_STAGE_NAME = "CI_JOB_STAGE";
  public static final String GITLAB_JOB_ID = "CI_JOB_ID";
  public static final String GITLAB_JOB_NAME = "CI_JOB_NAME";
  public static final String GITLAB_JOB_URL = "CI_JOB_URL";
  public static final String GITLAB_WORKSPACE_PATH = "CI_PROJECT_DIR";
  public static final String GITLAB_GIT_REPOSITORY_URL = "CI_REPOSITORY_URL";
  public static final String GITLAB_GIT_COMMIT = "CI_COMMIT_SHA";
  public static final String GITLAB_GIT_BRANCH = "CI_COMMIT_REF_NAME";
  public static final String GITLAB_GIT_TAG = "CI_COMMIT_TAG";
  public static final String GITLAB_GIT_COMMIT_MESSAGE = "CI_COMMIT_MESSAGE";
  public static final String GITLAB_GIT_COMMIT_AUTHOR = "CI_COMMIT_AUTHOR";
  public static final String GITLAB_GIT_COMMIT_TIMESTAMP = "CI_COMMIT_TIMESTAMP";
  public static final String GITLAB_CI_RUNNER_ID = "CI_RUNNER_ID";
  public static final String GITLAB_CI_RUNNER_TAGS = "CI_RUNNER_TAGS";
  public static final String GITLAB_PULL_REQUEST_BASE_BRANCH =
      "CI_MERGE_REQUEST_TARGET_BRANCH_NAME";
  public static final String GITLAB_PULL_REQUEST_COMMIT_HEAD_SHA =
      "CI_MERGE_REQUEST_SOURCE_BRANCH_SHA";
  public static final String GITLAB_PULL_REQUEST_NUMBER = "CI_MERGE_REQUEST_IID";

  private final CiEnvironment environment;

  GitLabInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(GITLAB_GIT_REPOSITORY_URL)),
        normalizeBranch(environment.get(GITLAB_GIT_BRANCH)),
        normalizeTag(environment.get(GITLAB_GIT_TAG)),
        new CommitInfo(
            environment.get(GITLAB_GIT_COMMIT),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            environment.get(GITLAB_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(GITLAB_PROVIDER_NAME)
        .ciPipelineId(environment.get(GITLAB_PIPELINE_ID))
        .ciPipelineName(environment.get(GITLAB_PIPELINE_NAME))
        .ciPipelineNumber(environment.get(GITLAB_PIPELINE_NUMBER))
        .ciPipelineUrl(environment.get(GITLAB_PIPELINE_URL))
        .ciStageName(environment.get(GITLAB_STAGE_NAME))
        .ciJobId(environment.get(GITLAB_JOB_ID))
        .ciJobName(environment.get(GITLAB_JOB_NAME))
        .ciJobUrl(environment.get(GITLAB_JOB_URL))
        .ciWorkspace(expandTilde(environment.get(GITLAB_WORKSPACE_PATH)))
        .ciNodeName(environment.get(GITLAB_CI_RUNNER_ID))
        .ciNodeLabels(environment.get(GITLAB_CI_RUNNER_TAGS))
        .ciEnvVars(GITLAB_PROJECT_URL, GITLAB_PIPELINE_ID, GITLAB_JOB_ID)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(GITLAB_PULL_REQUEST_BASE_BRANCH)),
        null,
        new CommitInfo(environment.get(GITLAB_PULL_REQUEST_COMMIT_HEAD_SHA)),
        environment.get(GITLAB_PULL_REQUEST_NUMBER));
  }

  private PersonInfo buildGitCommitAuthor() {
    final String gitAuthor = environment.get(GITLAB_GIT_COMMIT_AUTHOR);
    if (gitAuthor == null || gitAuthor.isEmpty()) {
      return PersonInfo.NOOP;
    }

    final PersonInfo personInfo = GitUtils.splitAuthorAndEmail(gitAuthor);
    return new PersonInfo(
        personInfo.getName(), personInfo.getEmail(), environment.get(GITLAB_GIT_COMMIT_TIMESTAMP));
  }

  @Override
  public Provider getProvider() {
    return Provider.GITLAB;
  }
}

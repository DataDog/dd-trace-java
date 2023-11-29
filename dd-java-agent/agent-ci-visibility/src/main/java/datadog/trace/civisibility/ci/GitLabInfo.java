package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitUtils;
import datadog.trace.api.git.PersonInfo;
import de.thetaphi.forbiddenapis.SuppressForbidden;

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

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(System.getenv(GITLAB_GIT_REPOSITORY_URL)),
        normalizeBranch(System.getenv(GITLAB_GIT_BRANCH)),
        normalizeTag(System.getenv(GITLAB_GIT_TAG)),
        new CommitInfo(
            System.getenv(GITLAB_GIT_COMMIT),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            System.getenv(GITLAB_GIT_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(GITLAB_PROVIDER_NAME)
        .ciPipelineId(System.getenv(GITLAB_PIPELINE_ID))
        .ciPipelineName(System.getenv(GITLAB_PIPELINE_NAME))
        .ciPipelineNumber(System.getenv(GITLAB_PIPELINE_NUMBER))
        .ciPipelineUrl(System.getenv(GITLAB_PIPELINE_URL))
        .ciStageName(System.getenv(GITLAB_STAGE_NAME))
        .ciJobName(System.getenv(GITLAB_JOB_NAME))
        .ciJobUrl(System.getenv(GITLAB_JOB_URL))
        .ciWorkspace(expandTilde(System.getenv(GITLAB_WORKSPACE_PATH)))
        .ciNodeName(System.getenv(GITLAB_CI_RUNNER_ID))
        .ciNodeLabels(System.getenv(GITLAB_CI_RUNNER_TAGS))
        .ciEnvVars(GITLAB_PROJECT_URL, GITLAB_PIPELINE_ID, GITLAB_JOB_ID)
        .build();
  }

  private PersonInfo buildGitCommitAuthor() {
    final String gitAuthor = System.getenv(GITLAB_GIT_COMMIT_AUTHOR);
    if (gitAuthor == null || gitAuthor.isEmpty()) {
      return PersonInfo.NOOP;
    }

    final PersonInfo personInfo = GitUtils.splitAuthorAndEmail(gitAuthor);
    return new PersonInfo(
        personInfo.getName(), personInfo.getEmail(), System.getenv(GITLAB_GIT_COMMIT_TIMESTAMP));
  }
}

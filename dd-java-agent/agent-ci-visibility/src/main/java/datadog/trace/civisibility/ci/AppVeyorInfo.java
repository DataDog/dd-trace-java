package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.util.Strings;
import javax.annotation.Nonnull;

class AppVeyorInfo implements CIProviderInfo {

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
  public static final String APPVEYOR_REPO_TAG_NAME = "APPVEYOR_REPO_TAG_NAME";
  public static final String APPVEYOR_REPO_COMMIT_MESSAGE_SUBJECT = "APPVEYOR_REPO_COMMIT_MESSAGE";
  public static final String APPVEYOR_REPO_COMMIT_MESSAGE_BODY =
      "APPVEYOR_REPO_COMMIT_MESSAGE_EXTENDED";
  public static final String APPVEYOR_REPO_COMMIT_AUTHOR_NAME = "APPVEYOR_REPO_COMMIT_AUTHOR";
  public static final String APPVEYOR_REPO_COMMIT_AUTHOR_EMAIL =
      "APPVEYOR_REPO_COMMIT_AUTHOR_EMAIL";
  public static final String APPVEYOR_PR_HEAD_REPO_BRANCH =
      "APPVEYOR_PULL_REQUEST_HEAD_REPO_BRANCH";
  public static final String APPVEYOR_PR_HEAD_COMMIT = "APPVEYOR_PULL_REQUEST_HEAD_COMMIT";
  public static final String APPVEYOR_PR_NUMBER = "APPVEYOR_PULL_REQUEST_NUMBER";

  private final CiEnvironment environment;

  AppVeyorInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    final String repoProvider = environment.get(APPVEYOR_REPO_PROVIDER);
    final String messageSubject = environment.get(APPVEYOR_REPO_COMMIT_MESSAGE_SUBJECT);
    final String messageBody = environment.get(APPVEYOR_REPO_COMMIT_MESSAGE_BODY);
    return new GitInfo(
        buildGitRepositoryUrl(repoProvider, environment.get(APPVEYOR_REPO_NAME)),
        buildGitBranch(repoProvider),
        buildGitTag(repoProvider),
        new CommitInfo(
            buildGitCommit(),
            buildGitCommitAuthor(),
            PersonInfo.NOOP,
            getCommitMessage(messageSubject, messageBody)));
  }

  private static String getCommitMessage(String messageSubject, String messageBody) {
    if (messageSubject == null) {
      return messageBody;
    }
    if (messageBody == null) {
      return messageSubject;
    }
    return String.format("%s%n%s", messageSubject, messageBody);
  }

  @Override
  public CIInfo buildCIInfo() {
    final String url =
        buildPipelineUrl(environment.get(APPVEYOR_REPO_NAME), environment.get(APPVEYOR_BUILD_ID));
    return CIInfo.builder(environment)
        .ciProviderName(APPVEYOR_PROVIDER_NAME)
        .ciPipelineId(environment.get(APPVEYOR_BUILD_ID))
        .ciPipelineName(environment.get(APPVEYOR_REPO_NAME))
        .ciPipelineNumber(environment.get(APPVEYOR_PIPELINE_NUMBER))
        .ciPipelineUrl(url)
        .ciJobUrl(url)
        .ciWorkspace(expandTilde(environment.get(APPVEYOR_WORKSPACE_PATH)))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    // check if PR is detected
    if (Strings.isNotBlank(environment.get(APPVEYOR_PR_HEAD_REPO_BRANCH))) {
      return new PullRequestInfo(
          normalizeBranch(environment.get(APPVEYOR_REPO_BRANCH)),
          null,
          environment.get(APPVEYOR_PR_HEAD_COMMIT),
          environment.get(APPVEYOR_PR_NUMBER));
    } else {
      return PullRequestInfo.EMPTY;
    }
  }

  private String buildGitBranch(final String repoProvider) {
    if ("github".equals(repoProvider)) {
      String branch = environment.get(APPVEYOR_PR_HEAD_REPO_BRANCH);
      if (branch == null || branch.isEmpty()) {
        branch = environment.get(APPVEYOR_REPO_BRANCH);
      }
      return normalizeBranch(branch);
    }
    return null;
  }

  private String buildGitTag(final String repoProvider) {
    if ("github".equals(repoProvider)) {
      return normalizeTag(environment.get(APPVEYOR_REPO_TAG_NAME));
    }
    return null;
  }

  private String buildGitCommit() {
    if ("github".equals(environment.get(APPVEYOR_REPO_PROVIDER))) {
      return environment.get(APPVEYOR_REPO_COMMIT);
    }
    return null;
  }

  private String buildGitRepositoryUrl(final String repoProvider, final String repoName) {
    if ("github".equals(repoProvider) && (repoName != null && !repoName.isEmpty())) {
      return String.format("https://github.com/%s.git", repoName);
    }
    return null;
  }

  private String buildPipelineUrl(final String repoName, final String buildId) {
    return String.format("https://ci.appveyor.com/project/%s/builds/%s", repoName, buildId);
  }

  private PersonInfo buildGitCommitAuthor() {
    return new PersonInfo(
        environment.get(APPVEYOR_REPO_COMMIT_AUTHOR_NAME),
        environment.get(APPVEYOR_REPO_COMMIT_AUTHOR_EMAIL));
  }

  @Override
  public Provider getProvider() {
    return Provider.APPVEYOR;
  }
}

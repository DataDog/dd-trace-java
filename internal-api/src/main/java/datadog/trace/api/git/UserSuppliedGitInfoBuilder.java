package datadog.trace.api.git;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.ConfigStrings;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserSuppliedGitInfoBuilder implements GitInfoBuilder {

  public static final String DD_GIT_REPOSITORY_URL = "git.repository.url";
  public static final String DD_GIT_BRANCH = "git.branch";
  public static final String DD_GIT_TAG = "git.tag";
  public static final String DD_GIT_COMMIT_SHA = "git.commit.sha";
  public static final String DD_GIT_COMMIT_MESSAGE = "git.commit.message";
  public static final String DD_GIT_COMMIT_AUTHOR_NAME = "git.commit.author.name";
  public static final String DD_GIT_COMMIT_AUTHOR_EMAIL = "git.commit.author.email";
  public static final String DD_GIT_COMMIT_AUTHOR_DATE = "git.commit.author.date";
  public static final String DD_GIT_COMMIT_COMMITTER_NAME = "git.commit.committer.name";
  public static final String DD_GIT_COMMIT_COMMITTER_EMAIL = "git.commit.committer.email";
  public static final String DD_GIT_COMMIT_COMMITTER_DATE = "git.commit.committer.date";
  private static final Logger log = LoggerFactory.getLogger(UserSuppliedGitInfoBuilder.class);

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    ConfigProvider configProvider = ConfigProvider.getInstance();

    String gitRepositoryUrl = configProvider.getString(DD_GIT_REPOSITORY_URL);
    if (gitRepositoryUrl == null) {
      gitRepositoryUrl = Config.get().getGlobalTags().get(Tags.GIT_REPOSITORY_URL);
    }

    // The user can set the DD_GIT_BRANCH manually but
    // using the value returned by the CI Provider, so
    // we need to normalize the value. Also, it can contain
    // the tag (e.g. origin/tags/0.1.0)
    String gitTag = configProvider.getString(DD_GIT_TAG);
    String gitBranch = null;
    final String gitBranchOrTag = configProvider.getString(DD_GIT_BRANCH);
    if (gitBranchOrTag != null) {
      if (!GitUtils.isTagReference(gitBranchOrTag)) {
        gitBranch = GitUtils.normalizeBranch(gitBranchOrTag);
      } else if (gitTag == null) {
        gitTag = GitUtils.normalizeTag(gitBranchOrTag);
      }
    }

    String gitCommitSha = configProvider.getString(DD_GIT_COMMIT_SHA);
    if (gitCommitSha == null) {
      gitCommitSha = Config.get().getGlobalTags().get(Tags.GIT_COMMIT_SHA);
    }

    final String gitCommitMessage = configProvider.getString(DD_GIT_COMMIT_MESSAGE);
    final String gitCommitAuthorName = configProvider.getString(DD_GIT_COMMIT_AUTHOR_NAME);
    final String gitCommitAuthorEmail = configProvider.getString(DD_GIT_COMMIT_AUTHOR_EMAIL);
    final String gitCommitAuthorDate = configProvider.getString(DD_GIT_COMMIT_AUTHOR_DATE);
    final String gitCommitCommitterName = configProvider.getString(DD_GIT_COMMIT_COMMITTER_NAME);
    final String gitCommitCommitterEmail = configProvider.getString(DD_GIT_COMMIT_COMMITTER_EMAIL);
    final String gitCommitCommitterDate = configProvider.getString(DD_GIT_COMMIT_COMMITTER_DATE);

    GitInfo gitInfo =
        new GitInfo(
            gitRepositoryUrl,
            gitBranch,
            gitTag,
            new CommitInfo(
                gitCommitSha,
                new PersonInfo(gitCommitAuthorName, gitCommitAuthorEmail, gitCommitAuthorDate),
                new PersonInfo(
                    gitCommitCommitterName, gitCommitCommitterEmail, gitCommitCommitterDate),
                gitCommitMessage));

    if (!gitInfo.isEmpty()) {
      // if there is any git metadata supplied by the user, we want to check that repo URL and
      // commit SHA are valid
      String repoUrl = gitInfo.getRepositoryURL();
      if (repoUrl == null || repoUrl.isEmpty()) {
        log.error(
            "Could not resolve git repository URL (can be provided via {} env var or corresponding system property, {} config property or by embedding git metadata at build time)",
            ConfigStrings.propertyNameToEnvironmentVariableName(DD_GIT_REPOSITORY_URL),
            GeneralConfig.TAGS);
      }

      String commitSha = gitInfo.getCommit().getSha();
      if (!GitUtils.isValidCommitShaFull(commitSha)) {
        log.error(
            "Git commit SHA could not be resolved or is invalid: {}"
                + " (can be provided via {}"
                + " env var or corresponding system property, {}"
                + " config property or by embedding git metadata at build time; must be a full-length SHA",
            commitSha,
            ConfigStrings.propertyNameToEnvironmentVariableName(DD_GIT_COMMIT_SHA),
            GeneralConfig.TAGS);
      }
    }

    return gitInfo;
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public GitProviderExpected providerAsExpected() {
    return GitProviderExpected.USER_SUPPLIED;
  }

  @Override
  public GitProviderDiscrepant providerAsDiscrepant() {
    return GitProviderDiscrepant.USER_SUPPLIED;
  }
}

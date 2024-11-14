package datadog.trace.api.git;

import static datadog.trace.api.git.GitInfo.DD_GIT_BRANCH;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_AUTHOR_DATE;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_AUTHOR_EMAIL;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_AUTHOR_NAME;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_COMMITTER_DATE;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_COMMITTER_EMAIL;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_COMMITTER_NAME;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_MESSAGE;
import static datadog.trace.api.git.GitInfo.DD_GIT_COMMIT_SHA;
import static datadog.trace.api.git.GitInfo.DD_GIT_REPOSITORY_URL;
import static datadog.trace.api.git.GitInfo.DD_GIT_TAG;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserSuppliedGitInfoBuilder implements GitInfoBuilder {

  private static final Logger log = LoggerFactory.getLogger(UserSuppliedGitInfoBuilder.class);

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    String gitRepositoryUrl = System.getenv(DD_GIT_REPOSITORY_URL);
    if (gitRepositoryUrl == null) {
      gitRepositoryUrl = Config.get().getGlobalTags().get(Tags.GIT_REPOSITORY_URL);
    }

    // The user can set the DD_GIT_BRANCH manually but
    // using the value returned by the CI Provider, so
    // we need to normalize the value. Also, it can contain
    // the tag (e.g. origin/tags/0.1.0)
    String gitTag = System.getenv(DD_GIT_TAG);
    String gitBranch = null;
    final String gitBranchOrTag = System.getenv(DD_GIT_BRANCH);
    if (gitBranchOrTag != null) {
      if (!GitUtils.isTagReference(gitBranchOrTag)) {
        gitBranch = GitUtils.normalizeBranch(gitBranchOrTag);
      } else if (gitTag == null) {
        gitTag = GitUtils.normalizeTag(gitBranchOrTag);
      }
    }

    String gitCommitSha = System.getenv(DD_GIT_COMMIT_SHA);
    if (gitCommitSha == null) {
      gitCommitSha = Config.get().getGlobalTags().get(Tags.GIT_COMMIT_SHA);
    }

    ConfigCollector.get().put(DD_GIT_REPOSITORY_URL, gitRepositoryUrl, ConfigOrigin.ENV);
    ConfigCollector.get().put(DD_GIT_COMMIT_SHA, gitCommitSha, ConfigOrigin.ENV);

    final String gitCommitMessage = System.getenv(DD_GIT_COMMIT_MESSAGE);
    final String gitCommitAuthorName = System.getenv(DD_GIT_COMMIT_AUTHOR_NAME);
    final String gitCommitAuthorEmail = System.getenv(DD_GIT_COMMIT_AUTHOR_EMAIL);
    final String gitCommitAuthorDate = System.getenv(DD_GIT_COMMIT_AUTHOR_DATE);
    final String gitCommitCommitterName = System.getenv(DD_GIT_COMMIT_COMMITTER_NAME);
    final String gitCommitCommitterEmail = System.getenv(DD_GIT_COMMIT_COMMITTER_EMAIL);
    final String gitCommitCommitterDate = System.getenv(DD_GIT_COMMIT_COMMITTER_DATE);

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
            "Could not resolve git repository URL (can be provided via "
                + GitInfo.DD_GIT_REPOSITORY_URL
                + " env var, "
                + GeneralConfig.TAGS
                + " config property or by embedding git metadata at build time)");
      }

      String commitSha = gitInfo.getCommit().getSha();
      if (!GitUtils.isValidCommitSha(commitSha)) {
        log.error(
            "Git commit SHA could not be resolved or is invalid: "
                + commitSha
                + " (can be provided via "
                + GitInfo.DD_GIT_COMMIT_SHA
                + " env var, "
                + GeneralConfig.TAGS
                + " config property or by embedding git metadata at build time; must be a full-length SHA_");
      }
    }

    return gitInfo;
  }

  @Override
  public int order() {
    return 0;
  }
}

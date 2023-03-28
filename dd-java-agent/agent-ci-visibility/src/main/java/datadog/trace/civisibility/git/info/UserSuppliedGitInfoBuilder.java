package datadog.trace.civisibility.git.info;

import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_BRANCH;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_AUTHOR_DATE;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_AUTHOR_EMAIL;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_AUTHOR_NAME;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_COMMITTER_DATE;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_COMMITTER_EMAIL;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_COMMITTER_NAME;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_MESSAGE;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_COMMIT_SHA;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_REPOSITORY_URL;
import static datadog.trace.api.civisibility.git.GitInfo.DD_GIT_TAG;

import datadog.trace.api.civisibility.git.CommitInfo;
import datadog.trace.api.civisibility.git.GitInfo;
import datadog.trace.api.civisibility.git.PersonInfo;
import datadog.trace.civisibility.git.GitUtils;

public class UserSuppliedGitInfoBuilder {
  public GitInfo build() {
    final String gitRepositoryUrl = System.getenv(DD_GIT_REPOSITORY_URL);

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
    final String gitCommitSha = System.getenv(DD_GIT_COMMIT_SHA);
    final String gitCommitMessage = System.getenv(DD_GIT_COMMIT_MESSAGE);
    final String gitCommitAuthorName = System.getenv(DD_GIT_COMMIT_AUTHOR_NAME);
    final String gitCommitAuthorEmail = System.getenv(DD_GIT_COMMIT_AUTHOR_EMAIL);
    final String gitCommitAuthorDate = System.getenv(DD_GIT_COMMIT_AUTHOR_DATE);
    final String gitCommitCommitterName = System.getenv(DD_GIT_COMMIT_COMMITTER_NAME);
    final String gitCommitCommitterEmail = System.getenv(DD_GIT_COMMIT_COMMITTER_EMAIL);
    final String gitCommitCommitterDate = System.getenv(DD_GIT_COMMIT_COMMITTER_DATE);

    return new GitInfo(
        gitRepositoryUrl,
        gitBranch,
        gitTag,
        new CommitInfo(
            gitCommitSha,
            new PersonInfo(gitCommitAuthorName, gitCommitAuthorEmail, gitCommitAuthorDate),
            new PersonInfo(gitCommitCommitterName, gitCommitCommitterEmail, gitCommitCommitterDate),
            gitCommitMessage));
  }
}

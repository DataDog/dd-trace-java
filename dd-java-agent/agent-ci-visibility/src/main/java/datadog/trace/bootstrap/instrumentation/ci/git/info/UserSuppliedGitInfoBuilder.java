package datadog.trace.bootstrap.instrumentation.ci.git.info;

import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_BRANCH;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_DATE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_EMAIL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_AUTHOR_NAME;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_DATE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_EMAIL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_COMMITTER_NAME;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_MESSAGE;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_COMMIT_SHA;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_REPOSITORY_URL;
import static datadog.trace.bootstrap.instrumentation.ci.git.GitInfo.DD_GIT_TAG;

import datadog.trace.bootstrap.instrumentation.ci.git.CommitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitUtils;
import datadog.trace.bootstrap.instrumentation.ci.git.PersonInfo;

public class UserSuppliedGitInfoBuilder {
  public GitInfo build() {
    final String gitRepositoryUrl = System.getenv(DD_GIT_REPOSITORY_URL);

    // The user can set the DD_GIT_BRANCH manually but
    // using the value returned by the CI Provider, so
    // we need to normalize the value. Also, it can contain
    // the tag (e.g. origin/tags/0.1.0)
    String gitTag = System.getenv(DD_GIT_TAG);
    String gitBranch = null;
    final String rawGitBranchOrTag = System.getenv(DD_GIT_BRANCH);
    if (rawGitBranchOrTag != null) {
      if (!rawGitBranchOrTag.contains("tags")) {
        gitBranch = GitUtils.normalizeRef(rawGitBranchOrTag);
      } else if (gitTag == null) {
        gitTag = GitUtils.normalizeRef(rawGitBranchOrTag);
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

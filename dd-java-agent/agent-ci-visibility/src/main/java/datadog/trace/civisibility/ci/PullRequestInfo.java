package datadog.trace.civisibility.ci;

import datadog.trace.util.Strings;
import java.util.Objects;

public class PullRequestInfo {

  public static final PullRequestInfo EMPTY = new PullRequestInfo(null, null, null, null);

  private final String pullRequestBaseBranch;
  private final String pullRequestBaseBranchSha;
  private final String gitCommitHeadSha;
  private final String pullRequestNumber;

  public PullRequestInfo(
      String pullRequestBaseBranch,
      String pullRequestBaseBranchSha,
      String gitCommitHeadSha,
      String pullRequestNumber) {
    this.pullRequestBaseBranch = pullRequestBaseBranch;
    this.pullRequestBaseBranchSha = pullRequestBaseBranchSha;
    this.gitCommitHeadSha = gitCommitHeadSha;
    this.pullRequestNumber = pullRequestNumber;
  }

  public String getPullRequestBaseBranch() {
    return pullRequestBaseBranch;
  }

  public String getPullRequestBaseBranchSha() {
    return pullRequestBaseBranchSha;
  }

  public String getGitCommitHeadSha() {
    return gitCommitHeadSha;
  }

  public String getPullRequestNumber() {
    return pullRequestNumber;
  }

  public boolean isNotEmpty() {
    return Strings.isNotBlank(pullRequestBaseBranch)
        || Strings.isNotBlank(pullRequestBaseBranchSha)
        || Strings.isNotBlank(gitCommitHeadSha)
        || Strings.isNotBlank(pullRequestNumber);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(pullRequestBaseBranch)
        && Strings.isNotBlank(pullRequestBaseBranchSha)
        && Strings.isNotBlank(gitCommitHeadSha)
        && Strings.isNotBlank(pullRequestNumber);
  }

  /**
   * Merges info by completing the empty information fields with the fallback's
   *
   * @param info Base PR info
   * @param fallback Fallback PR info
   * @return Completed PR info
   */
  public static PullRequestInfo merge(PullRequestInfo info, PullRequestInfo fallback) {
    return new PullRequestInfo(
        Strings.isNotBlank(info.pullRequestBaseBranch)
            ? info.pullRequestBaseBranch
            : fallback.pullRequestBaseBranch,
        Strings.isNotBlank(info.pullRequestBaseBranchSha)
            ? info.pullRequestBaseBranchSha
            : fallback.pullRequestBaseBranchSha,
        Strings.isNotBlank(info.gitCommitHeadSha)
            ? info.gitCommitHeadSha
            : fallback.gitCommitHeadSha,
        Strings.isNotBlank(info.pullRequestNumber)
            ? info.pullRequestNumber
            : fallback.pullRequestNumber);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PullRequestInfo that = (PullRequestInfo) o;
    return Objects.equals(pullRequestBaseBranch, that.pullRequestBaseBranch)
        && Objects.equals(pullRequestBaseBranchSha, that.pullRequestBaseBranchSha)
        && Objects.equals(gitCommitHeadSha, that.gitCommitHeadSha)
        && Objects.equals(pullRequestNumber, that.pullRequestNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pullRequestBaseBranch, pullRequestBaseBranchSha, gitCommitHeadSha, pullRequestNumber);
  }

  @Override
  public String toString() {
    return "PR{"
        + "baseBranch='"
        + pullRequestBaseBranch
        + '\''
        + ", baseSHA='"
        + pullRequestBaseBranchSha
        + '\''
        + ", commitSHA='"
        + gitCommitHeadSha
        + '\''
        + ", prNumber='"
        + pullRequestNumber
        + '\''
        + '}';
  }
}

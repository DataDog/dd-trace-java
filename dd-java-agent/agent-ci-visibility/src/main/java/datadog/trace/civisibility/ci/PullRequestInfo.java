package datadog.trace.civisibility.ci;

import datadog.trace.util.Strings;
import java.util.Objects;

public class PullRequestInfo {

  public static final PullRequestInfo EMPTY = new PullRequestInfo(null, null, null);

  private final String pullRequestBaseBranch;
  private final String pullRequestBaseBranchSha;
  private final String gitCommitHeadSha;

  public PullRequestInfo(
      String pullRequestBaseBranch, String pullRequestBaseBranchSha, String gitCommitHeadSha) {
    this.pullRequestBaseBranch = pullRequestBaseBranch;
    this.pullRequestBaseBranchSha = pullRequestBaseBranchSha;
    this.gitCommitHeadSha = gitCommitHeadSha;
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

  public boolean isNotEmpty() {
    return Strings.isNotBlank(pullRequestBaseBranch)
        || Strings.isNotBlank(pullRequestBaseBranchSha)
        || Strings.isNotBlank(gitCommitHeadSha);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(pullRequestBaseBranch)
        && Strings.isNotBlank(pullRequestBaseBranchSha)
        && Strings.isNotBlank(gitCommitHeadSha);
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
            : fallback.gitCommitHeadSha);
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
        && Objects.equals(gitCommitHeadSha, that.gitCommitHeadSha);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pullRequestBaseBranch, pullRequestBaseBranchSha, gitCommitHeadSha);
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
        + '}';
  }
}

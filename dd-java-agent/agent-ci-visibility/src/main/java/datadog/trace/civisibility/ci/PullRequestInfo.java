package datadog.trace.civisibility.ci;

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
    return "PullRequestInfo{"
        + "pullRequestBaseBranch='"
        + pullRequestBaseBranch
        + '\''
        + ", pullRequestBaseBranchSha='"
        + pullRequestBaseBranchSha
        + '\''
        + ", gitCommitHeadSha='"
        + gitCommitHeadSha
        + '\''
        + '}';
  }
}

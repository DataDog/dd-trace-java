package datadog.trace.civisibility.ci;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.util.Strings;
import java.util.Objects;
import javax.annotation.Nonnull;

public class PullRequestInfo {

  public static final PullRequestInfo EMPTY =
      new PullRequestInfo(null, null, CommitInfo.NOOP, null);

  private final String pullRequestBaseBranch;
  private final String pullRequestBaseBranchSha;
  @Nonnull private final CommitInfo headCommit;
  private final String pullRequestNumber;

  public PullRequestInfo(
      String pullRequestBaseBranch,
      String pullRequestBaseBranchSha,
      @Nonnull CommitInfo headCommit,
      String pullRequestNumber) {
    this.pullRequestBaseBranch = pullRequestBaseBranch;
    this.pullRequestBaseBranchSha = pullRequestBaseBranchSha;
    this.headCommit = headCommit;
    this.pullRequestNumber = pullRequestNumber;
  }

  public String getPullRequestBaseBranch() {
    return pullRequestBaseBranch;
  }

  public String getPullRequestBaseBranchSha() {
    return pullRequestBaseBranchSha;
  }

  @Nonnull
  public CommitInfo getHeadCommit() {
    return headCommit;
  }

  public String getPullRequestNumber() {
    return pullRequestNumber;
  }

  public boolean isEmpty() {
    return Strings.isBlank(pullRequestBaseBranch)
        && Strings.isBlank(pullRequestBaseBranchSha)
        && headCommit.isEmpty()
        && Strings.isBlank(pullRequestNumber);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(pullRequestBaseBranch)
        && Strings.isNotBlank(pullRequestBaseBranchSha)
        && headCommit.isComplete()
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
        CommitInfo.merge(info.headCommit, fallback.headCommit),
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
        && Objects.equals(headCommit, that.headCommit)
        && Objects.equals(pullRequestNumber, that.pullRequestNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pullRequestBaseBranch, pullRequestBaseBranchSha, headCommit, pullRequestNumber);
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
        + ", headCommit='"
        + headCommit
        + '\''
        + ", prNumber='"
        + pullRequestNumber
        + '\''
        + '}';
  }
}

package datadog.trace.civisibility.ci;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.util.Strings;
import java.util.Objects;
import javax.annotation.Nonnull;

public class PullRequestInfo {

  public static final PullRequestInfo EMPTY =
      new PullRequestInfo(null, null, null, CommitInfo.NOOP, null);

  private final String baseBranch;
  private final String baseBranchSha;
  private final String baseBranchHeadSha;
  @Nonnull private final CommitInfo headCommit;
  private final String pullRequestNumber;

  public PullRequestInfo(
      String baseBranch,
      String baseBranchSha,
      String baseBranchHeadSha,
      @Nonnull CommitInfo headCommit,
      String pullRequestNumber) {
    this.baseBranch = baseBranch;
    this.baseBranchSha = baseBranchSha;
    this.baseBranchHeadSha = baseBranchHeadSha;
    this.headCommit = headCommit;
    this.pullRequestNumber = pullRequestNumber;
  }

  public String getBaseBranch() {
    return baseBranch;
  }

  public String getBaseBranchSha() {
    return baseBranchSha;
  }

  public String getBaseBranchHeadSha() {
    return baseBranchHeadSha;
  }

  @Nonnull
  public CommitInfo getHeadCommit() {
    return headCommit;
  }

  public String getPullRequestNumber() {
    return pullRequestNumber;
  }

  public boolean isEmpty() {
    return Strings.isBlank(baseBranch)
        && Strings.isBlank(baseBranchSha)
        && Strings.isBlank(baseBranchHeadSha)
        && headCommit.isEmpty()
        && Strings.isBlank(pullRequestNumber);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(baseBranch)
        && Strings.isNotBlank(baseBranchSha)
        && Strings.isNotBlank(baseBranchHeadSha)
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
        Strings.isNotBlank(info.baseBranch) ? info.baseBranch : fallback.baseBranch,
        Strings.isNotBlank(info.baseBranchSha) ? info.baseBranchSha : fallback.baseBranchSha,
        Strings.isNotBlank(info.baseBranchHeadSha)
            ? info.baseBranchHeadSha
            : fallback.baseBranchHeadSha,
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
    return Objects.equals(baseBranch, that.baseBranch)
        && Objects.equals(baseBranchSha, that.baseBranchSha)
        && Objects.equals(baseBranchHeadSha, that.baseBranchHeadSha)
        && Objects.equals(headCommit, that.headCommit)
        && Objects.equals(pullRequestNumber, that.pullRequestNumber);
  }

  @Override
  public int hashCode() {
    return Objects.hash(baseBranch, baseBranchSha, headCommit, pullRequestNumber);
  }

  @Override
  public String toString() {
    return "PR{"
        + "baseBranch='"
        + baseBranch
        + '\''
        + ", baseSHA='"
        + baseBranchSha
        + '\''
        + ", baseHeadSHA='"
        + baseBranchHeadSha
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

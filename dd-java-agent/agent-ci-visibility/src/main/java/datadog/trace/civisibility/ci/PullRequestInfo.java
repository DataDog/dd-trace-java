package datadog.trace.civisibility.ci;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.util.Strings;
import java.util.Objects;
import javax.annotation.Nonnull;
import datadog.trace.util.HashingUtils;

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
   * Combine infos by completing the empty information fields in {@code first} with {@code second}'s
   *
   * @param first Base PR info
   * @param second Fallback PR info
   * @return Combined PR info
   */
  public static PullRequestInfo coalesce(
      final PullRequestInfo first, final PullRequestInfo second) {
    return new PullRequestInfo(
        Strings.coalesce(first.baseBranch, second.baseBranch),
        Strings.coalesce(first.baseBranchSha, second.baseBranchSha),
        Strings.coalesce(first.baseBranchHeadSha, second.baseBranchHeadSha),
        CommitInfo.coalesce(first.headCommit, second.headCommit),
        Strings.coalesce(first.pullRequestNumber, second.pullRequestNumber));
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
    return HashingUtils.hash(baseBranch, baseBranchSha, headCommit, pullRequestNumber);
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

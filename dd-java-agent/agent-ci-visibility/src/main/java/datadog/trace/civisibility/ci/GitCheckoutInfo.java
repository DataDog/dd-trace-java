package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.isFullLengthCommitSha;
import static datadog.trace.util.Strings.isBlank;

import datadog.trace.api.git.GitInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Compares the tested checkout with the CI provider's repository identity. */
public final class GitCheckoutInfo {
  private GitCheckoutInfo() {}

  public static boolean samePath(String left, String right) {
    if (isBlank(left) || isBlank(right)) {
      return false;
    }
    try {
      Path a = Paths.get(left).toAbsolutePath().normalize();
      Path b = Paths.get(right).toAbsolutePath().normalize();
      return a.equals(b) || Files.isSameFile(a, b);
    } catch (Exception ignored) {
      return false;
    }
  }

  public static boolean matchesRevision(
      String sha, GitInfo provider, PullRequestInfo pr, String mergeSha) {
    return sameRevision(sha, provider.getCommit().getSha())
        || sameRevision(sha, pr.getHeadCommit().getSha())
        || sameRevision(sha, mergeSha);
  }

  public static boolean sameRevision(String left, String right) {
    return isFullLengthCommitSha(left)
        && isFullLengthCommitSha(right)
        && left.equalsIgnoreCase(right);
  }
}

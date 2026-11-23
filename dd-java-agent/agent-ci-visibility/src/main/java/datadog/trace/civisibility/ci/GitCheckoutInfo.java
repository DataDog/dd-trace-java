package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.isFullLengthCommitSha;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_PR_HEAD_COMMIT;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_PR_NUMBER;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_REPO_COMMIT;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_REPO_PROVIDER;
import static datadog.trace.util.Strings.isBlank;
import static datadog.trace.util.Strings.isNotBlank;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

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

  public static String mergeCommitSha(Provider provider, CiEnvironment env) {
    String sha = null;
    switch (provider) {
      case APPVEYOR:
        String number = env.get(APPVEYOR_PR_NUMBER);
        String head = env.get(APPVEYOR_PR_HEAD_COMMIT);
        String build = env.get(APPVEYOR_REPO_COMMIT);
        if ("github".equals(env.get(APPVEYOR_REPO_PROVIDER))
            && number != null
            && number.matches("[0-9]+")
            && isFullLengthCommitSha(head)
            && !head.equalsIgnoreCase(build)) {
          sha = build;
        }
        break;
      case GITHUBACTIONS:
        if (isMergeRef(env.get("GITHUB_REF"))) {
          sha = env.get("GITHUB_SHA");
        }
        break;
      case GITLAB:
        String type = env.get("CI_MERGE_REQUEST_EVENT_TYPE");
        if (isNotBlank(env.get("CI_MERGE_REQUEST_IID"))
            && ("merged_result".equals(type) || "merge_train".equals(type))) {
          sha = env.get("CI_COMMIT_SHA");
        }
        break;
      case AZP:
        if ("PullRequest".equals(env.get("BUILD_REASON"))
            && isMergeRef(env.get("BUILD_SOURCEBRANCH"))) {
          sha = env.get("BUILD_SOURCEVERSION");
        }
        break;
      case TRAVISCI:
        String pr = env.get("TRAVIS_PULL_REQUEST");
        if ("pull_request".equals(env.get("TRAVIS_EVENT_TYPE"))
            && pr != null
            && pr.matches("[0-9]+")) {
          sha = env.get("TRAVIS_COMMIT");
        }
        break;
      default:
        break;
    }
    return isFullLengthCommitSha(sha) ? sha.toLowerCase(Locale.ROOT) : null;
  }

  private static boolean isMergeRef(String ref) {
    return ref != null && ref.matches("refs/pull/[0-9]+/merge");
  }
}

package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfo;
import javax.annotation.Nonnull;

/**
 * Provider strategy used when Bazel payload-in-files mode is active. The orchestrating Bazel rule
 * is responsible for collecting CI/git information and enriching the test spans downstream (e.g.
 * combining with the underlying CI provider to produce {@code bazel/github}). This strategy only
 * surfaces {@link Provider#BAZEL} for telemetry so the rule can confirm that the agent detected the
 * mode; no environment or git data is read here.
 */
class BazelInfo implements CIProviderInfo {

  @Override
  public GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.NOOP;
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return PullRequestInfo.EMPTY;
  }

  @Override
  public Provider getProvider() {
    return Provider.BAZEL;
  }
}

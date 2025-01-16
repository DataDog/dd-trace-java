package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfo;
import javax.annotation.Nonnull;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();

  @Nonnull
  PullRequestInfo buildPullRequestInfo();

  Provider getProvider();
}

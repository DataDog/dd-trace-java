package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.normalizeBranch;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

public class TeamcityInfo implements CIProviderInfo {
  public static final String TEAMCITY = "TEAMCITY_VERSION";
  public static final String TEAMCITY_PROVIDER_NAME = "teamcity";
  private static final String TEAMCITY_BUILDCONF_NAME = "TEAMCITY_BUILDCONF_NAME";
  private static final String BUILD_URL = "BUILD_URL";
  private static final String TEAMCITY_PULL_REQUEST_NUMBER = "TEAMCITY_PULLREQUEST_NUMBER";
  private static final String TEAMCITY_PULL_REQUEST_TARGET_BRANCH =
      "TEAMCITY_PULLREQUEST_TARGET_BRANCH";

  private final CiEnvironment environment;

  TeamcityInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(null, null, null, new CommitInfo(null));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(TEAMCITY_PROVIDER_NAME)
        .ciJobName(environment.get(TEAMCITY_BUILDCONF_NAME))
        .ciJobUrl(environment.get(BUILD_URL))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(TEAMCITY_PULL_REQUEST_TARGET_BRANCH)),
        null,
        null,
        CommitInfo.NOOP,
        environment.get(TEAMCITY_PULL_REQUEST_NUMBER));
  }

  @Override
  public Provider getProvider() {
    return Provider.TEAMCITY;
  }
}

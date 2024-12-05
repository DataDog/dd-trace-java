package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;

public class TeamcityInfo implements CIProviderInfo {
  public static final String TEAMCITY = "TEAMCITY_VERSION";
  public static final String TEAMCITY_PROVIDER_NAME = "teamcity";
  private static final String TEAMCITY_BUILDCONF_NAME = "TEAMCITY_BUILDCONF_NAME";
  private static final String BUILD_URL = "BUILD_URL";

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

  @Override
  public Provider getProvider() {
    return Provider.TEAMCITY;
  }
}

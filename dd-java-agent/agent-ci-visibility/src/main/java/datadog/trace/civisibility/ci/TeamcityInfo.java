package datadog.trace.civisibility.ci;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;

public class TeamcityInfo implements CIProviderInfo {
  public static final String TEAMCITY = "TEAMCITY_VERSION";
  public static final String TEAMCITY_PROVIDER_NAME = "teamcity";
  private static final String TEAMCITY_BUILDCONF_NAME = "TEAMCITY_BUILDCONF_NAME";
  private static final String BUILD_URL = "BUILD_URL";

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(null, null, null, new CommitInfo(null));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(TEAMCITY_PROVIDER_NAME)
        .ciJobName(System.getenv(TEAMCITY_BUILDCONF_NAME))
        .ciJobUrl(System.getenv(BUILD_URL))
        .build();
  }
}

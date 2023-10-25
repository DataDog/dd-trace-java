package datadog.trace.civisibility.ci

class TeamcityInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return TeamcityInfo.TEAMCITY_PROVIDER_NAME
  }
}

package datadog.trace.civisibility.ci

class TeamcityInfoTest extends CITagsProviderImplTest {

  @Override
  String getProviderName() {
    return TeamcityInfo.TEAMCITY_PROVIDER_NAME
  }
}

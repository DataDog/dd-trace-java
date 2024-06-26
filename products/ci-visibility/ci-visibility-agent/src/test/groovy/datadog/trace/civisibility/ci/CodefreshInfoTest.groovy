package datadog.trace.civisibility.ci

class CodefreshInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return CodefreshInfo.CODEFRESH_PROVIDER_NAME
  }
}

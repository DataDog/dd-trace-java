package datadog.trace.civisibility.ci

class CodefreshInfoTest extends CITagsProviderImplTest {

  @Override
  String getProviderName() {
    return CodefreshInfo.CODEFRESH_PROVIDER_NAME
  }
}

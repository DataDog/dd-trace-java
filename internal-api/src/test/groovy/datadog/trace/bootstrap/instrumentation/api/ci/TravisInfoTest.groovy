package datadog.trace.bootstrap.instrumentation.api.ci

class TravisInfoTest extends CIProviderInfoTest {
  
  @Override
  CIProviderInfo instanceProvider() {
    return new TravisInfo()
  }

  @Override
  String getProviderName() {
    return TravisInfo.TRAVIS_PROVIDER_NAME
  }
}

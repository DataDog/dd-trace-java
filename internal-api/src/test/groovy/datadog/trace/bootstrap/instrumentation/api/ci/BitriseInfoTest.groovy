package datadog.trace.bootstrap.instrumentation.api.ci

class BitriseInfoTest extends CIProviderInfoTest {
  @Override
  CIProviderInfo instanceProvider() {
    return new BitriseInfo()
  }

  @Override
  String getProviderName() {
    return BitriseInfo.BITRISE_PROVIDER_NAME
  }
}

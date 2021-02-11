package datadog.trace.bootstrap.instrumentation.ci


class AzurePipelinesInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new AzurePipelinesInfo()
  }

  @Override
  String getProviderName() {
    return AzurePipelinesInfo.AZURE_PROVIDER_NAME
  }
}

package datadog.trace.bootstrap.instrumentation.api.ci

class AppVeyorInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new AppVeyorInfo()
  }

  @Override
  String getProviderName() {
    return AppVeyorInfo.APPVEYOR_PROVIDER_NAME
  }
}

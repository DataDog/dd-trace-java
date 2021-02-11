package datadog.trace.bootstrap.instrumentation.ci

class CircleCIInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new CircleCIInfo()
  }

  @Override
  String getProviderName() {
    return CircleCIInfo.CIRCLECI_PROVIDER_NAME
  }
}

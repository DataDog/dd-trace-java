package datadog.trace.bootstrap.instrumentation.api.ci

class NoopCIInfoTest extends CIProviderInfoTest {
  @Override
  CIProviderInfo instanceProvider() {
    return new NoopCIInfo()
  }

  @Override
  String getProviderName() {
    return NoopCIInfo.NOOP_PROVIDER_NAME
  }
}

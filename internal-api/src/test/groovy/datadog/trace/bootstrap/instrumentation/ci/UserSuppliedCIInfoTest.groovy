package datadog.trace.bootstrap.instrumentation.ci

class UserSuppliedCIInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new NoopCIInfo()
  }

  @Override
  String getProviderName() {
    return "usersupplied"
  }
}

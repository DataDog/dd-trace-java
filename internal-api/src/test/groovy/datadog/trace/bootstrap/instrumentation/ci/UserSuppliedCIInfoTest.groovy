package datadog.trace.bootstrap.instrumentation.ci

class UserSuppliedCIInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new UnknownCIInfo()
  }

  @Override
  String getProviderName() {
    return "usersupplied"
  }
}

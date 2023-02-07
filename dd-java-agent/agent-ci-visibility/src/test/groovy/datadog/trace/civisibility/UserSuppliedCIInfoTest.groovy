package datadog.trace.civisibility

class UserSuppliedCIInfoTest extends CITagsProviderImplTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new UnknownCIInfo()
  }

  @Override
  String getProviderName() {
    return "usersupplied"
  }
}

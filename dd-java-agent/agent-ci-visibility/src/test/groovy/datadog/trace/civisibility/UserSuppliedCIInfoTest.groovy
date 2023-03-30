package datadog.trace.civisibility

class UserSuppliedCIInfoTest extends CITagsProviderImplTest {

  @Override
  String getProviderName() {
    return "usersupplied"
  }
}

package datadog.trace.civisibility.ci

class UserSuppliedCIInfoTest extends CITagsProviderImplTest {

  @Override
  String getProviderName() {
    return "usersupplied"
  }

  boolean isCi() {
    false
  }
}

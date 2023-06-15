package datadog.trace.civisibility.ci

class UserSuppliedCIInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return "usersupplied"
  }

  boolean isCi() {
    false
  }
}

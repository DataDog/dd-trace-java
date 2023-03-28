package datadog.trace.civisibility

import datadog.trace.api.civisibility.CIProviderInfo

import java.nio.file.Paths

class UserSuppliedCIInfoTest extends CITagsProviderImplTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new UnknownCIInfo(Paths.get("").toAbsolutePath())
  }

  @Override
  String getProviderName() {
    return "usersupplied"
  }
}

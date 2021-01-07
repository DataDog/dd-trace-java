package datadog.trace.bootstrap.instrumentation.api.ci

class GithubActionsInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new GithubActionsInfo()
  }

  @Override
  String getProviderName() {
    return GithubActionsInfo.GHACTIONS_PROVIDER_NAME
  }
}

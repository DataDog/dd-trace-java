package datadog.trace.bootstrap.instrumentation.api.ci

class GitLabInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new GitLabInfo()
  }

  @Override
  String getProviderName() {
    return GitLabInfo.GITLAB_PROVIDER_NAME
  }
}

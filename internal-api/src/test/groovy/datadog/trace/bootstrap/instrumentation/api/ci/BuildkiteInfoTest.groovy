package datadog.trace.bootstrap.instrumentation.api.ci

class BuildkiteInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new BuildkiteInfo()
  }

  @Override
  String getProviderName() {
    return BuildkiteInfo.BUILDKITE_PROVIDER_NAME
  }
}

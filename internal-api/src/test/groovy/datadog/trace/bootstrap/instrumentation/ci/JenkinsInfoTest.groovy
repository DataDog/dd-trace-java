package datadog.trace.bootstrap.instrumentation.ci

class JenkinsInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new JenkinsInfo()
  }

  @Override
  String getProviderName() {
    return JenkinsInfo.JENKINS_PROVIDER_NAME
  }
}

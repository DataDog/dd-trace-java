package datadog.trace.bootstrap.instrumentation.ci

class NoopCIInfoTest extends CIProviderInfoTest {
  @Override
  CIProviderInfo instanceProvider() {
    return new NoopCIInfo()
  }

  @Override
  String getProviderName() {
    return NoopCIInfo.NOOP_PROVIDER_NAME
  }

  def "test isCi is false"() {
    when:
    def provider = instanceProvider()

    then:
    !provider.isCI()
  }
}

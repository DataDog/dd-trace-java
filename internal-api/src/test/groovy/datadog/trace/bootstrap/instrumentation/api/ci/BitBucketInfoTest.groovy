package datadog.trace.bootstrap.instrumentation.api.ci

class BitBucketInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new BitBucketInfo()
  }

  @Override
  String getProviderName() {
    return BitBucketInfo.BITBUCKET_PROVIDER_NAME
  }
}

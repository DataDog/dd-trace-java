package datadog.trace.api.iast

class MockIASTModule implements IASTModule {

  IASTModule mock

  @Override
  void onCipher(final String algorithm) {
    mock.onCipher(algorithm)
  }

  @Override
  void onHash(final String algorithm) {
    mock.onHash(algorithm)
  }
}

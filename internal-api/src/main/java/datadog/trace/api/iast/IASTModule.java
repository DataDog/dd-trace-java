package datadog.trace.api.iast;

public interface IASTModule {

  void onCipher(final String algorithm);

  void onHash(final String algorithm);
}

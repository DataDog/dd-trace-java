package datadog.trace.api.iast;

public interface IASTModule {

  void onCipher(String algorithm);

  void onHash(String algorithm);
}

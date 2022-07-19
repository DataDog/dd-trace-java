package datadog.trace.api.iast;

public interface IASTModule {

  void onCipherAlgorithm(String algorithm);

  void onHashingAlgorithm(String algorithm);
}

package datadog.trace.api.iast;

import javax.annotation.Nonnull;

public interface IastModule {

  void onCipherAlgorithm(@Nonnull String algorithm);

  void onHashingAlgorithm(@Nonnull String algorithm);
}

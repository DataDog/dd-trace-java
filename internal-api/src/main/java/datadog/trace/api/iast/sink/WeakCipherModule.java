package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface WeakCipherModule extends IastModule {

  void onCipherAlgorithm(@Nonnull String algorithm);
}

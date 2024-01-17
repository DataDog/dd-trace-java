package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface HardcodedSecretModule extends IastModule {

  void onHardcodedSecret(
      @Nonnull String value, @Nonnull String method, @Nonnull String clazz, int currentLine);
}

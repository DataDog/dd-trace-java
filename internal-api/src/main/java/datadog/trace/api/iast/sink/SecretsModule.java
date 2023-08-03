package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface SecretsModule extends IastModule {

  void onStringLiteral(
      @Nonnull final String value, @Nonnull final String clazz, final @Nonnull byte[] classFile);
}

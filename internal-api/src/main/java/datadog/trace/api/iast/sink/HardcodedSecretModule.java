package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.util.Set;
import javax.annotation.Nonnull;

public interface HardcodedSecretModule extends IastModule {

  void onStringLiteral(
      @Nonnull final Set<String> literals,
      @Nonnull final String clazz,
      final @Nonnull byte[] classFile);
}

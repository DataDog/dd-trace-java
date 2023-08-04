package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import java.util.Set;

public interface SecretsModule extends IastModule {

  void onStringLiteral(
      @Nonnull final Set<String> literals, @Nonnull final String clazz, final @Nonnull byte[] classFile);
}

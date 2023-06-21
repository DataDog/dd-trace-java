package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface UnvalidatedRedirectModule extends IastModule {

  void onRedirect(@Nullable String value);

  void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method);

  void onURIRedirect(@Nullable URI value);

  void onHeader(@Nonnull String name, String value);
}

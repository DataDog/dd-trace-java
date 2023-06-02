package datadog.trace.api.iast.sink;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URI;

public interface UnvalidatedRedirectModule extends HttpHeaderModule {

  void onRedirect(@Nullable String value);

  void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method);

  void onURIRedirect(@Nullable URI value);
}

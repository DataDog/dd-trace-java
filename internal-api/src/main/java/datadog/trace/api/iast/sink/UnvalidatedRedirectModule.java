package datadog.trace.api.iast.sink;

import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface UnvalidatedRedirectModule extends HttpHeaderModule {

  void onRedirect(@Nullable String value);

  void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method);

  void onURIRedirect(@Nullable URI value);
}

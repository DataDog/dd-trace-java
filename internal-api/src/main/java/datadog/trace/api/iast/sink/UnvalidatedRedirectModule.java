package datadog.trace.api.iast.sink;

import java.net.URI;
import javax.annotation.Nullable;

public interface UnvalidatedRedirectModule extends HttpHeaderModule {

  void onRedirect(@Nullable String value);

  void onRedirect(@Nullable String value, @Nullable String clazz, @Nullable String method);

  void onURIRedirect(@Nullable URI value);
}

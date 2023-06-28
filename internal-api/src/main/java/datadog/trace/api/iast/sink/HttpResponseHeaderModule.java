package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface HttpResponseHeaderModule extends IastModule {

  void onHeader(@Nonnull String name, String value);

  void onCookie(
      @Nonnull String name, boolean isSecure, boolean isHttpOnly, boolean isSameSiteStrict);
}

package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public interface HttpResponseHeaderModule extends IastModule {

  void onHeader(@Nonnull String name, String value);

  void onCookie(@Nonnull Cookie cookie);
}

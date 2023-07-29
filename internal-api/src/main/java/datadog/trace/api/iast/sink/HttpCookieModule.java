package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.util.Cookie;
import javax.annotation.Nonnull;

public interface HttpCookieModule<T> extends IastModule {

  boolean isVulnerable(@Nonnull final Cookie cookie);

  T getType();
}

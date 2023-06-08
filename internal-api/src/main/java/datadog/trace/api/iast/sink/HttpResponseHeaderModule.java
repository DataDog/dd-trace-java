package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface HttpResponseHeaderModule extends IastModule {
  public void onCookie(
      String cookieName, boolean isSecure, boolean isHttpOnly, Boolean isSameSiteStrict);

  public void onHeader(@Nonnull final String name, final String value);

  public void addDelegate(@Nonnull final ForHeader forHeader);

  public void addDelegate(@Nonnull final ForCookie forCookie);

  public void clearDelegates();

  interface ForHeader extends IastModule {
    void onHeader(@Nonnull String name, String value);
  }

  interface ForCookie extends IastModule {}
}

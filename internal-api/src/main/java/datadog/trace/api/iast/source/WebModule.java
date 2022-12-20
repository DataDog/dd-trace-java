package datadog.trace.api.iast.source;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface WebModule extends IastModule {

  /**
   * An HTTP request parameter name is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterName(@Nullable String paramName);

  /**
   * An HTTP request parameter value is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterValue(@Nullable String paramName, @Nullable String paramValue);

  void onHeaderName(@Nullable String headerName);

  void onHeaderValue(@Nullable String headerName, @Nullable String headerValue);

  <COOKIE> void onCookies(@Nullable COOKIE[] cookies);

  <COOKIE> void onCookieGetter(
      @Nonnull COOKIE self,
      @Nullable String cookieName,
      @Nullable String result,
      byte sourceTypeValue);
}

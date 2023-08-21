package datadog.trace.api.iast.source;

import datadog.trace.api.iast.IastModule;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface WebModule extends IastModule {

  /**
   * An HTTP request parameter name is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterNames(@Nullable Collection<String> paramNames);
  /**
   * An HTTP request parameter value is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterValue(@Nullable String paramName, @Nullable String paramValue);

  void onParameterValues(@Nullable String paramName, @Nullable String[] paramValue);

  void onParameterValues(@Nullable String paramName, @Nullable Collection<String> paramValues);

  void onParameterValues(@Nullable Map<String, String[]> values);

  void onHeaderNames(@Nullable Collection<String> headerNames);

  void onHeaderValue(@Nullable String headerName, @Nullable String headerValue);

  void onHeaderValues(@Nullable String headerName, @Nullable Collection<String> headerValue);

  void onQueryString(@Nullable String queryString);

  void onCookieNames(@Nullable Iterable<String> cookieNames);

  void onCookieValue(@Nullable String cookieName, @Nullable String cookieValue);

  void onRequestPathParameter(
      @Nullable String paramName, @Nullable String value, @Nonnull Object iastRequestContext);

  void onRequestMatrixParameter(
      @Nonnull String paramName, @Nullable String value, @Nonnull Object iastRequestContext);
}

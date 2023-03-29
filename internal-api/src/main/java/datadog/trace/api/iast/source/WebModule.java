package datadog.trace.api.iast.source;

import datadog.trace.api.iast.IastModule;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface WebModule extends IastModule {

  /**
   * HTTP request parameter names are used. This should be used when it cannot be determined whether
   * the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterNames(@Nullable Collection<String> paramNames);

  void onParameterName(@Nullable String paramName, @Nonnull Object iastRequestContext);

  /**
   * An HTTP request parameter value is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterValue(@Nullable String paramName, @Nullable String paramValue);

  void onParameterValue(
      @Nullable String paramName, @Nullable String paramValue, @Nonnull Object iastRequestContext);

  void onParameterValues(@Nullable String paramName, @Nullable String[] paramValue);

  void onParameterValues(@Nullable String paramName, @Nullable Collection<String> paramValues);

  void onParameterValues(@Nullable Map<String, String[]> values);

  void onHeaderName(@Nullable String name, @Nonnull Object iastRequestContext);

  void onHeaderNames(@Nullable Collection<String> headerNames);

  void onHeaderValue(@Nullable String headerName, @Nullable String headerValue);

  void onHeaderValue(
      @Nullable String headerName,
      @Nullable String headerValue,
      @Nonnull Object iastRequestContext);

  void onHeaderValues(@Nullable String headerName, @Nullable Collection<String> headerValue);

  void onQueryString(@Nullable String queryString);

  void onCookieNames(@Nullable Iterable<String> cookieNames);

  void onCookieValue(@Nullable String cookieName, @Nullable String cookieValue);

  void onRequestPathParameter(
      @Nullable String paramName, @Nullable String value, @Nonnull Object iastRequestContext);

  void onRequestMatrixParameter(
      @Nonnull String paramName, @Nullable String value, @Nonnull Object iastRequestContext);
}

package datadog.trace.api.iast.source;

import datadog.trace.api.iast.IastModule;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;

public interface WebModule extends IastModule {

  /**
   * An HTTP request parameter name is used. This should be used when it cannot be determined
   * whether the parameter comes in the query string or body (e.g. servlet's getParameter).
   */
  void onParameterNames(@Nullable Collection<String> paramNames);

  void onParameterValues(@Nullable String paramName, @Nullable String[] paramValue);

  void onParameterValues(@Nullable String paramName, @Nullable Collection<String> paramValues);

  void onParameterValues(@Nullable Map<String, String[]> values);

  void onHeaderNames(@Nullable Collection<String> headerNames);

  void onHeaderValues(@Nullable String headerName, @Nullable Collection<String> headerValue);

  void onCookieNames(@Nullable Iterable<String> cookieNames);
}

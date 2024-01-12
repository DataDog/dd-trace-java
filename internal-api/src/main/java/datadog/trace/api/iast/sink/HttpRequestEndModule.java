package datadog.trace.api.iast.sink;

import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.IastModule;
import java.net.HttpURLConnection;
import java.util.Locale;

public interface HttpRequestEndModule extends IastModule {

  void onRequestEnd(IastContext ctx, IGSpanInfo span);

  default boolean isHtmlResponse(final String value) {
    if (value == null) {
      return false;
    }
    final String contentType = value.toLowerCase(Locale.ROOT);
    return contentType.contains("text/html") || contentType.contains("application/xhtml+xml");
  }

  default boolean isIgnorableResponseCode(final Integer httpStatus) {
    if (httpStatus == null) {
      return false;
    }
    return httpStatus == HttpURLConnection.HTTP_MOVED_PERM
        || httpStatus == HttpURLConnection.HTTP_MOVED_TEMP
        || httpStatus == HttpURLConnection.HTTP_NOT_MODIFIED
        || httpStatus == HttpURLConnection.HTTP_NOT_FOUND
        || httpStatus == HttpURLConnection.HTTP_GONE
        || httpStatus == HttpURLConnection.HTTP_INTERNAL_ERROR
        || httpStatus == 307;
  }
}

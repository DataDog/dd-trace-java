package datadog.communication;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import javax.annotation.Nullable;
import okhttp3.RequestBody;

/** API for posting HTTP requests to backend */
public interface BackendApi {

  <T> T post(
      String uri,
      RequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException;

  /**
   * Posts an HTTP request with caller-supplied headers.
   *
   * <p>The default implementation preserves compatibility with backends that do not support custom
   * headers.
   */
  default <T> T post(
      String uri,
      RequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener,
      boolean requestCompression,
      Map<String, String> requestHeaders)
      throws IOException {
    return post(uri, requestBody, responseParser, requestListener, requestCompression);
  }
}

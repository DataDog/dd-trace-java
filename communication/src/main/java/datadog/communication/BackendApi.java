package datadog.communication;

import datadog.communication.http.HttpUtils;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/** API for posting HTTP requests to backend */
public interface BackendApi {

  <T> T post(
      String uri,
      HttpRequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable HttpUtils.CustomListener requestListener,
      boolean requestCompression)
      throws IOException;
}

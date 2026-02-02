package datadog.communication;

import datadog.communication.util.IOThrowingFunction;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpRequestListener;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/** API for posting HTTP requests to backend */
public interface BackendApi {

  <T> T post(
      String uri,
      String contentType,
      HttpRequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable HttpRequestListener requestListener,
      boolean requestCompression)
      throws IOException;
}

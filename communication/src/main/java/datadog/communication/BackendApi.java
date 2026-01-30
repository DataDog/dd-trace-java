package datadog.communication;

import datadog.communication.util.IOThrowingFunction;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpRequestBody;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/** API for posting HTTP requests to backend */
public interface BackendApi {

  <T> T post(
      String uri,
      String contextType,
      HttpRequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable HttpRequestListener requestListener,
      boolean requestCompression)
      throws IOException;
}

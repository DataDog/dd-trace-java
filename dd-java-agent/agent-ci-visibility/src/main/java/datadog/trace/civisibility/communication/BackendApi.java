package datadog.trace.civisibility.communication;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.civisibility.utils.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import okhttp3.RequestBody;

/** API for posting HTTP requests to backend */
public interface BackendApi {

  <T> T post(
      String uri,
      RequestBody requestBody,
      IOThrowingFunction<InputStream, T> responseParser,
      @Nullable OkHttpUtils.CustomListener requestListener)
      throws IOException;
}

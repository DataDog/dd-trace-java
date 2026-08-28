package datadog.communication;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class BackendApiTest {

  @Test
  void defaultPostFallsBackToPostWithoutHeaders() throws IOException {
    final BackendApi api = new TestBackendApi();

    final String response =
        api.post(
            "flagevaluation",
            null,
            input -> "response",
            null,
            false,
            singletonMap("DD-EVP-ORIGIN", "dd-trace-java"));

    assertEquals("response", response);
  }

  private static final class TestBackendApi implements BackendApi {
    @Override
    public <T> T post(
        final String uri,
        final RequestBody requestBody,
        final IOThrowingFunction<InputStream, T> responseParser,
        @Nullable final OkHttpUtils.CustomListener requestListener,
        final boolean requestCompression)
        throws IOException {
      return responseParser.apply(new ByteArrayInputStream(new byte[0]));
    }
  }
}

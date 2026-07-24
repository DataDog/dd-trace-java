package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class OkHttpUtilsRetryTest {

  @Test
  void retriesResponseMappingFailureThroughCallFactory() throws Exception {
    final MockWebServer server = new MockWebServer();
    final OkHttpClient client = new OkHttpClient();
    final AtomicInteger mappingAttempts = new AtomicInteger();
    server.enqueue(new MockResponse().setBody("first"));
    server.enqueue(new MockResponse().setBody("second"));
    server.start();

    try {
      final Request request = new Request.Builder().url(server.url("/configuration")).build();

      final String body =
          sendWithRetries(
              (Call.Factory) client,
              new HttpRetryPolicy.Factory(1, 0, 1),
              request,
              response -> {
                try (ResponseBody responseBody = response.body()) {
                  if (mappingAttempts.getAndIncrement() == 0) {
                    throw new ConnectException("response body could not be mapped");
                  }
                  return responseBody.string();
                }
              });

      assertEquals("second", body);
      assertEquals(2, mappingAttempts.get());
      assertEquals(2, server.getRequestCount());
    } finally {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
      server.shutdown();
    }
  }
}

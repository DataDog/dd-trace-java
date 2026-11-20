package datadog.communication.http;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.gzippedRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @Test
  void retriesMsgpackByteBufferRequestBodyPreservingPayload() throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(503));
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start();

    final OkHttpClient client = OkHttpUtils.buildHttpClient(server.url("/"), 5_000);
    try {
      final byte[] expected = "header:payload-body".getBytes(UTF_8);
      final Request request =
          new Request.Builder()
              .url(server.url("/v0.4/traces"))
              .post(msgpackRequestBodyOf(createMultiBufferPayload()))
              .build();

      try (Response response =
          sendWithRetries(client, new HttpRetryPolicy.Factory(2, 0, 1), request)) {
        assertEquals(200, response.code());
      }

      assertEquals(2, server.getRequestCount());
      for (int i = 0; i < 2; i++) {
        final RecordedRequest recorded = server.takeRequest();
        assertEquals(String.valueOf(expected.length), recorded.getHeader("Content-Length"));
        assertArrayEquals(expected, recorded.getBody().readByteArray());
      }
    } finally {
      shutdown(client, server);
    }
  }

  @Test
  void retriesGzippedMsgpackByteBufferRequestBodyPreservingPayload() throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(503));
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start();

    final OkHttpClient client = OkHttpUtils.buildHttpClient(server.url("/"), 5_000);
    try {
      final byte[] expected = "header:payload-body".getBytes(UTF_8);
      final Request request =
          new Request.Builder()
              .url(server.url("/api/v2/citestcycle"))
              .addHeader("Content-Encoding", "gzip")
              .post(gzippedMsgpackRequestBodyOf(createMultiBufferPayload()))
              .build();

      try (Response response =
          sendWithRetries(client, new HttpRetryPolicy.Factory(2, 0, 1), request)) {
        assertEquals(200, response.code());
      }

      assertEquals(2, server.getRequestCount());
      for (int i = 0; i < 2; i++) {
        final RecordedRequest recorded = server.takeRequest();
        assertArrayEquals(expected, gunzip(recorded.getBody().readByteArray()));
      }
    } finally {
      shutdown(client, server);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retriesMultipartWrappingByteBufferRequestBodyPreservingPayload(boolean compressed)
      throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(503));
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start();

    final OkHttpClient client = OkHttpUtils.buildHttpClient(server.url("/"), 5_000);
    try {
      final RequestBody coverageBody = msgpackRequestBodyOf(createMultiBufferPayload());
      final MultipartBody multipartBody =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("coverage1", "coverage1.msgpack", coverageBody)
              .addFormDataPart(
                  "event",
                  "event.json",
                  RequestBody.create(MediaType.get("application/json"), "{\"dummy\":true}"))
              .build();
      final Request.Builder requestBuilder =
          new Request.Builder()
              .url(server.url("/api/v2/citestcov"))
              .post(compressed ? gzippedRequestBodyOf(multipartBody) : multipartBody);
      if (compressed) {
        requestBuilder.addHeader("Content-Encoding", "gzip");
      }

      try (Response response =
          sendWithRetries(client, new HttpRetryPolicy.Factory(2, 0, 1), requestBuilder.build())) {
        assertEquals(200, response.code());
      }

      assertEquals(2, server.getRequestCount());
      final byte[] firstRaw = server.takeRequest().getBody().readByteArray();
      final byte[] secondRaw = server.takeRequest().getBody().readByteArray();
      final byte[] firstBody = compressed ? gunzip(firstRaw) : firstRaw;
      final byte[] secondBody = compressed ? gunzip(secondRaw) : secondRaw;
      assertTrue(new String(firstBody, UTF_8).contains("header:payload-body"));
      assertArrayEquals(firstBody, secondBody);
    } finally {
      shutdown(client, server);
    }
  }

  @Test
  void okHttpAutomaticRetryOnStaleKeepAlivePreservesByteBufferRequestBody() throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start();

    final OkHttpClient client = OkHttpUtils.buildHttpClient(server.url("/"), 5_000);
    try {
      // Warm up a pooled keep-alive connection, just like normal agent traffic.
      try (Response warmup =
          client.newCall(new Request.Builder().url(server.url("/info")).get().build()).execute()) {
        assertEquals(200, warmup.code());
      }
      server.takeRequest();

      final byte[] expected = "header:payload-body".getBytes(UTF_8);
      final Request request =
          new Request.Builder()
              .url(server.url("/v0.4/traces"))
              .put(msgpackRequestBodyOf(createMultiBufferPayload()))
              .build();

      try (Response response = client.newCall(request).execute()) {
        assertEquals(200, response.code());
      }

      assertEquals(3, server.getRequestCount());
      for (int i = 0; i < 2; i++) {
        final RecordedRequest recorded = server.takeRequest();
        assertEquals(String.valueOf(expected.length), recorded.getHeader("Content-Length"));
        assertArrayEquals(expected, recorded.getBody().readByteArray());
      }
    } finally {
      shutdown(client, server);
    }
  }

  @Test
  void okHttpAutomaticRetryOn503RetryAfterZeroPreservesByteBufferRequestBody() throws Exception {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(503).addHeader("Retry-After", "0"));
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start();

    final OkHttpClient client = OkHttpUtils.buildHttpClient(server.url("/"), 5_000);
    try {
      final byte[] expected = "header:payload-body".getBytes(UTF_8);
      final Request request =
          new Request.Builder()
              .url(server.url("/v0.4/traces"))
              .put(msgpackRequestBodyOf(createMultiBufferPayload()))
              .build();

      try (Response response = client.newCall(request).execute()) {
        assertEquals(200, response.code());
      }

      assertEquals(2, server.getRequestCount());
      for (int i = 0; i < 2; i++) {
        final RecordedRequest recorded = server.takeRequest();
        assertEquals(String.valueOf(expected.length), recorded.getHeader("Content-Length"));
        assertArrayEquals(expected, recorded.getBody().readByteArray());
      }
    } finally {
      shutdown(client, server);
    }
  }

  private static List<ByteBuffer> createMultiBufferPayload() {
    final ByteBuffer slicedHeap = ByteBuffer.wrap("xxheader:".getBytes(UTF_8), 2, 7);
    final byte[] bodyBytes = "payload-body".getBytes(UTF_8);
    final ByteBuffer direct = ByteBuffer.allocateDirect(bodyBytes.length);
    direct.put(bodyBytes);
    direct.flip();
    return Arrays.asList(slicedHeap, direct);
  }

  private static byte[] gunzip(byte[] compressed) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      final byte[] buf = new byte[1024];
      int read;
      while ((read = in.read(buf)) != -1) {
        out.write(buf, 0, read);
      }
    }
    return out.toByteArray();
  }

  private static void shutdown(OkHttpClient client, MockWebServer server) throws IOException {
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
    server.shutdown();
  }
}

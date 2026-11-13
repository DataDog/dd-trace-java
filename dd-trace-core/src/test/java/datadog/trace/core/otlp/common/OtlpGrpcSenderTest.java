package datadog.trace.core.otlp.common;

import static datadog.trace.core.otlp.common.OtlpPayload.PROTOBUF_CONTENT_TYPE;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class OtlpGrpcSenderTest {

  private static final String SIGNAL_PATH =
      "/opentelemetry.proto.collector.trace.v1.TraceService/Export";

  @TableTest({
    "scenario                 | grpcStatus | contentType                      ",
    "trailers-only OK         | 0          | application/grpc                 ",
    "status in trailers       |            | application/grpc                 ",
    "status in proto trailers |            | application/grpc+proto           ",
    "mixed case content type  |            | Application/GRPC                 ",
    "content type parameters  |            | 'application/grpc; charset=utf-8'"
  })
  void grpcOkResponseIsReturnedAsSuccess(String grpcStatus, String contentType) {
    RemoteApi.Response result = send(200, grpcStatus, null, contentType);

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
  }

  @TableTest({
    "scenario             | grpcStatus | grpcMessage   | contentType          | expectedCode",
    "unavailable          | 14         | 'unavailable' | application/grpc     | 14          ",
    "unauthenticated      | 16         | 'bad api key' | application/grpc     | 16          ",
    "without message      | 8          |               | application/grpc     | 8           ",
    "unparseable status   | abc        |               | application/grpc     | 2           ",
    "not a gRPC response  |            |               | text/html            | 2           ",
    "gRPC-Web response    |            |               | application/grpc-web | 2           ",
    "without content type |            |               |                      | 2           "
  })
  void grpcErrorResponseIsReturnedAsFailureWithTheCodeInItsMessage(
      String grpcStatus, String grpcMessage, String contentType, int expectedCode) {
    RemoteApi.Response result = send(200, grpcStatus, grpcMessage, contentType);

    assertFalse(result.success());
    assertFalse(result.status().isPresent());
    IOException error = assertInstanceOf(IOException.class, result.exception().orElse(null));
    assertTrue(error.getMessage().startsWith("gRPC status " + expectedCode));
    if (grpcMessage != null) {
      assertTrue(error.getMessage().endsWith(": " + grpcMessage));
    }
  }

  @TableTest({
    "scenario         | grpcMessage       | expectedMessage",
    "plain            | 'bad api key'     | 'bad api key'  ",
    "encoded space    | 'bad%20api%20key' | 'bad api key'  ",
    "encoded unicode  | 'caf%C3%A9'       | 'café'         ",
    "literal plus     | 'a+b%20c'         | 'a+b c'        ",
    "encoded plus     | 'a%2Bb'           | 'a+b'          ",
    "encoded percent  | '100%25'          | '100%'         ",
    "malformed escape | '100%zz'          | '100%zz'       ",
    "truncated escape | '50%'             | '50%'          "
  })
  void grpcErrorMessageIsPercentDecoded(String grpcMessage, String expectedMessage) {
    RemoteApi.Response result = send(200, "16", grpcMessage, "application/grpc");

    assertEquals(
        "gRPC status 16: " + expectedMessage,
        result.exception().map(Throwable::getMessage).orElse(null));
  }

  @Test
  void unsuccessfulHttpResponseIsReturnedWithItsHttpStatus() {
    RemoteApi.Response result = send(503, null, null, "text/plain");

    assertFalse(result.success());
    assertEquals(503, result.status().getAsInt());
    assertFalse(result.exception().isPresent());
  }

  @Test
  void ioExceptionIsReturnedAsFailure() {
    IOException exception = new IOException("boom");
    OtlpGrpcSender sender = sender("http://localhost:4317");
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils.when(() -> OkHttpUtils.sendWithRetries(any(), any(), any())).thenThrow(exception);

      RemoteApi.Response result = sender.send(payload());

      assertFalse(result.success());
      assertEquals(exception, result.exception().orElse(null));
    } finally {
      sender.shutdown();
    }
  }

  @Test
  void grpcErrorStatusInHeadersIsAFailureWithTheCodeInItsMessage() throws Exception {
    // a "trailers-only" response: the status arrives with the headers of an HTTP 200 response
    MockResponse unauthenticated =
        new MockResponse()
            .setResponseCode(200)
            .addHeader("content-type", "application/grpc")
            .addHeader("grpc-status", "16")
            .addHeader("grpc-message", "bad api key");

    RemoteApi.Response response = sendOverHttp2(unauthenticated);

    assertFalse(response.success());
    IOException error = assertInstanceOf(IOException.class, response.exception().orElse(null));
    assertEquals("gRPC status 16: bad api key", error.getMessage());
  }

  /** Sends a payload, answering it with the given (mocked) response. */
  private static RemoteApi.Response send(
      int code, String grpcStatus, String grpcMessage, String contentType) {
    OtlpGrpcSender sender = sender("http://localhost:4317");
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(any(), any(), any()))
          .thenAnswer(invocation ->
              grpcResponse(invocation.getArgument(2), code, grpcStatus, grpcMessage, contentType));
      return sender.send(payload());
    } finally {
      sender.shutdown();
    }
  }

  /** Sends a payload to a real HTTP/2 server, answering it with the given response. */
  private static RemoteApi.Response sendOverHttp2(MockResponse mockResponse)
      throws IOException, InterruptedException {
    try (MockWebServer server = new MockWebServer()) {
      server.setProtocols(singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
      server.enqueue(mockResponse);
      server.start();

      OtlpGrpcSender sender = sender("http://" + server.getHostName() + ":" + server.getPort());
      try {
        RemoteApi.Response response = sender.send(payload());

        RecordedRequest request = server.takeRequest(5, SECONDS);
        assertNotNull(request);
        assertEquals(SIGNAL_PATH, request.getPath());
        assertEquals(1, server.getRequestCount());
        return response;
      } finally {
        sender.shutdown();
      }
    }
  }

  private static Response grpcResponse(
      Request request, int code, String grpcStatus, String grpcMessage, String contentType) {
    Response.Builder builder =
        new Response.Builder()
            .request(request)
            .protocol(Protocol.H2_PRIOR_KNOWLEDGE)
            .code(code)
            .message(code == 200 ? "OK" : "Server Error")
            .body(ResponseBody.create(MediaType.get("text/plain"), ""));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    if (grpcStatus != null) {
      builder.header("grpc-status", grpcStatus);
    }
    if (grpcMessage != null) {
      builder.header("grpc-message", grpcMessage);
    }
    return builder.build();
  }

  private static OtlpPayload payload() {
    return new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3}), PROTOBUF_CONTENT_TYPE);
  }

  private static OtlpGrpcSender sender(String endpoint) {
    return new OtlpGrpcSender(endpoint, SIGNAL_PATH, emptyMap(), 5_000, Compression.NONE);
  }
}

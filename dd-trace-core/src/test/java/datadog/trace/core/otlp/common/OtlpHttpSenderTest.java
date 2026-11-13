package datadog.trace.core.otlp.common;

import static datadog.trace.core.otlp.common.OtlpPayload.PROTOBUF_CONTENT_TYPE;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class OtlpHttpSenderTest {

  private final OtlpHttpSender sender =
      new OtlpHttpSender(
          "http://localhost:4318/v1/traces", "/v1/traces", emptyMap(), 5_000, Compression.NONE);

  @AfterEach
  void shutdown() {
    sender.shutdown();
  }

  @Test
  void successfulResponseIsReturnedAsSuccess() {
    RemoteApi.Response result = send(request -> responseBuilder(request, 200).build());

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
  }

  @Test
  void unsuccessfulResponseIsReturnedWithItsStatus() {
    RemoteApi.Response result = send(request -> responseBuilder(request, 500).build());

    assertFalse(result.success());
    assertEquals(500, result.status().getAsInt());
  }

  @Test
  void ioExceptionIsReturnedAsFailure() {
    IOException exception = new IOException("boom");
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils.when(() -> OkHttpUtils.sendWithRetries(any(), any(), any())).thenThrow(exception);

      RemoteApi.Response result = sender.send(payload());

      assertFalse(result.success());
      assertEquals(exception, result.exception().orElse(null));
    }
  }

  @Test
  void grpcStatusIsIgnored() {
    RemoteApi.Response result =
        send(request ->
            responseBuilder(request, 200)
                .header("grpc-status", "14")
                .header("grpc-message", "down")
                .build());

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
  }

  private RemoteApi.Response send(Function<Request, Response> responder) {
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(any(), any(), any()))
          .thenAnswer(invocation -> responder.apply(invocation.getArgument(2)));
      return sender.send(payload());
    }
  }

  private static OtlpPayload payload() {
    return new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3}), PROTOBUF_CONTENT_TYPE);
  }

  private static Response.Builder responseBuilder(Request request, int code) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code == 200 ? "OK" : "Server Error")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""));
  }
}

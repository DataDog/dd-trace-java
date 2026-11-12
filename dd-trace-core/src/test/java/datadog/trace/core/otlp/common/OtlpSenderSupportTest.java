package datadog.trace.core.otlp.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.logging.RatelimitedLogger;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.tabletest.junit.TableTest;

class OtlpSenderSupportTest {

  private final OkHttpClient client = mock(OkHttpClient.class);
  private final HttpRetryPolicy.Factory retryPolicy = HttpRetryPolicy.Factory.NEVER_RETRY;
  private final Request request =
      new Request.Builder().url("http://localhost:4318/v1/traces").build();
  private final RatelimitedLogger ratelimitedLogger = mock(RatelimitedLogger.class);

  @Test
  void successfulResponseIsReturnedWithoutLogging() throws IOException {
    RemoteApi.Response result = send(responseWithCode(200), false);

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
    verify(ratelimitedLogger, never()).warn(any(String.class), any());
  }

  @Test
  void unsuccessfulResponseIsReturnedAndLogged() throws IOException {
    RemoteApi.Response result = send(responseWithCode(500), false);

    assertFalse(result.success());
    assertEquals(500, result.status().getAsInt());
    verify(ratelimitedLogger).warn(any(String.class), any(), any(), any());
  }

  @Test
  void ioExceptionIsReturnedAsFailureAndLogged() throws IOException {
    IOException exception = new IOException("boom");
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(client, retryPolicy, request))
          .thenThrow(exception);

      RemoteApi.Response result =
          OtlpSenderSupport.send(client, retryPolicy, request, ratelimitedLogger);

      assertFalse(result.success());
      assertTrue(result.exception().isPresent());
      assertEquals(exception, result.exception().get());
      verify(ratelimitedLogger).warn(any(String.class), any(), any());
    }
  }

  @Test
  void httpResponseIgnoresGrpcStatus() throws IOException {
    Response response =
        responseBuilder(200).header("grpc-status", "14").header("grpc-message", "down").build();

    RemoteApi.Response result = send(response, false);

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
    verifyNoInteractions(ratelimitedLogger);
  }

  @TableTest({
    "scenario                 | grpcStatus | contentType                      ",
    "trailers-only OK         | 0          | application/grpc                 ",
    "status in trailers       |            | application/grpc                 ",
    "status in proto trailers |            | application/grpc+proto           ",
    "mixed case content type  |            | Application/GRPC                 ",
    "content type parameters  |            | 'application/grpc; charset=utf-8'"
  })
  void grpcOkResponseIsReturnedAsSuccessWithoutLogging(String grpcStatus, String contentType)
      throws IOException {
    RemoteApi.Response result = send(grpcResponse(200, grpcStatus, null, contentType), true);

    assertTrue(result.success());
    assertEquals(200, result.status().getAsInt());
    verifyNoInteractions(ratelimitedLogger);
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
      String grpcStatus, String grpcMessage, String contentType, int expectedCode)
      throws IOException {
    RemoteApi.Response result = send(grpcResponse(200, grpcStatus, grpcMessage, contentType), true);

    assertFalse(result.success());
    assertFalse(result.status().isPresent());
    IOException error = assertInstanceOf(IOException.class, result.exception().orElse(null));
    assertTrue(error.getMessage().startsWith("gRPC status " + expectedCode));
    if (grpcMessage != null) {
      assertTrue(error.getMessage().endsWith(": " + grpcMessage));
    }
    verify(ratelimitedLogger)
        .warn(any(String.class), eq(request.url()), contains("gRPC status " + expectedCode));
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
  void grpcErrorMessageIsPercentDecoded(String grpcMessage, String expectedMessage)
      throws IOException {
    RemoteApi.Response result =
        send(grpcResponse(200, "16", grpcMessage, "application/grpc"), true);

    String expected = "gRPC status 16: " + expectedMessage;
    assertEquals(expected, result.exception().map(Throwable::getMessage).orElse(null));
    verify(ratelimitedLogger).warn(any(String.class), eq(request.url()), eq(expected));
  }

  @Test
  void grpcUnsuccessfulHttpResponseIsReturnedWithItsHttpStatus() throws IOException {
    RemoteApi.Response result = send(grpcResponse(503, null, null, "text/plain"), true);

    assertFalse(result.success());
    assertEquals(503, result.status().getAsInt());
    assertFalse(result.exception().isPresent());
    verify(ratelimitedLogger).warn(any(String.class), any(), any(), any());
  }

  private RemoteApi.Response send(Response response, boolean grpc) throws IOException {
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(client, retryPolicy, request))
          .thenReturn(response);
      return grpc
          ? OtlpSenderSupport.send(client, retryPolicy, request, ratelimitedLogger, true)
          : OtlpSenderSupport.send(client, retryPolicy, request, ratelimitedLogger);
    }
  }

  private Response responseWithCode(int code) {
    return responseBuilder(code).build();
  }

  private Response grpcResponse(
      int code, String grpcStatus, String grpcMessage, String contentType) {
    Response.Builder builder = responseBuilder(code).protocol(Protocol.H2_PRIOR_KNOWLEDGE);
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

  private Response.Builder responseBuilder(int code) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code == 200 ? "OK" : "Server Error")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""));
  }
}

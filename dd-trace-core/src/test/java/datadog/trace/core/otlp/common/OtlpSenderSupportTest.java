package datadog.trace.core.otlp.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

class OtlpSenderSupportTest {

  private final OkHttpClient client = mock(OkHttpClient.class);
  private final HttpRetryPolicy.Factory retryPolicy = HttpRetryPolicy.Factory.NEVER_RETRY;
  private final Request request =
      new Request.Builder().url("http://localhost:4318/v1/traces").build();
  private final RatelimitedLogger ratelimitedLogger = mock(RatelimitedLogger.class);

  @Test
  void successfulResponseIsReturnedWithoutLogging() throws IOException {
    Response response = responseWithCode(200);
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(client, retryPolicy, request))
          .thenReturn(response);

      RemoteApi.Response result =
          OtlpSenderSupport.send(client, retryPolicy, request, ratelimitedLogger);

      assertTrue(result.success());
      assertEquals(200, result.status().getAsInt());
      verify(ratelimitedLogger, never()).warn(any(String.class), any());
    }
  }

  @Test
  void unsuccessfulResponseIsReturnedAndLogged() throws IOException {
    Response response = responseWithCode(500);
    try (MockedStatic<OkHttpUtils> okHttpUtils = mockStatic(OkHttpUtils.class)) {
      okHttpUtils
          .when(() -> OkHttpUtils.sendWithRetries(client, retryPolicy, request))
          .thenReturn(response);

      RemoteApi.Response result =
          OtlpSenderSupport.send(client, retryPolicy, request, ratelimitedLogger);

      assertFalse(result.success());
      assertEquals(500, result.status().getAsInt());
      verify(ratelimitedLogger).warn(any(String.class), any(), any(), any());
    }
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

  private Response responseWithCode(int code) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code == 200 ? "OK" : "Server Error")
        .body(ResponseBody.create(MediaType.get("text/plain"), ""))
        .build();
  }
}

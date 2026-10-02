package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppSecInterceptorTest {

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  private Interceptor.Chain chain;
  private Request request;
  private final AppSecInterceptor interceptor = new AppSecInterceptor();

  @BeforeEach
  void setup() {
    request = new Request.Builder().url("http://example.com").build();

    final RequestContext requestContext = mock(RequestContext.class);

    final AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(requestContext);
    when(span.getSpanId()).thenReturn(1L);
    when(span.getTag(Tags.HTTP_URL)).thenReturn("http://example.com");

    final AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class)))
        .thenReturn(CallbackProvider.CallbackProviderNoop.INSTANCE);
    AgentTracer.forceRegister(tracer);

    chain = mock(Interceptor.Chain.class);
    when(chain.request()).thenReturn(request);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void ioExceptionFromProceedPropagatesWithoutRetry() throws IOException {
    final IOException failure = new IOException("boom");
    when(chain.proceed(request)).thenThrow(failure);

    final IOException thrown = assertThrows(IOException.class, () -> interceptor.intercept(chain));

    assertSame(failure, thrown);
    verify(chain, times(1)).proceed(request);
  }

  @Test
  void responseHookFailureAfterBodyCapturePreservesCapturedBody() throws IOException {
    final String json = "{\"hello\":\"world\"}";
    final Response response =
        new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create(MediaType.parse("application/json"), json))
            .build();
    when(chain.proceed(request)).thenReturn(response);

    final CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.httpClientSampling()))
        .thenReturn((ctx, requestId) -> new Flow.ResultFlow<>(Boolean.TRUE));
    when(cbp.getCallback(EVENTS.httpClientResponse()))
        .thenReturn(
            (ctx, clientResponse) -> {
              throw new RuntimeException("boom");
            });
    when(AgentTracer.get().getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);

    final Response result = interceptor.intercept(chain);

    assertEquals(json, result.body().string());
  }
}

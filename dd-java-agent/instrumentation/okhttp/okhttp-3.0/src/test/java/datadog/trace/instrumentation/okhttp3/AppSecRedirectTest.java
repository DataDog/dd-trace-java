package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.HttpClientResponse;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.okhttp3.AppSecRetryAndFollowUpInstrumentation.FollowUpRequestAdvice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AppSecInterceptor#onRedirect(Response, Request)} and the body cleanup of {@link
 * AppSecRetryAndFollowUpInstrumentation.FollowUpRequestAdvice}, including the cleanup of a blocked
 * redirect.
 */
class AppSecRedirectTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();
  private final List<HttpClientRequest> requests = new ArrayList<>();
  private final List<HttpClientResponse> responses = new ArrayList<>();

  private AgentTracer.TracerAPI tracer;
  private RequestContext ctx;
  private BiFunction<RequestContext, HttpClientRequest, Flow<Void>> requestCallback;
  private BiFunction<RequestContext, HttpClientResponse, Flow<Void>> responseCallback;
  private Request followUp;
  private boolean bodyClosed;

  @BeforeEach
  void setup() {
    followUp = new Request.Builder().url("http://example.com/json").build();
    ctx = mock(RequestContext.class);
    when(ctx.getData(RequestContextSlot.APPSEC)).thenReturn(new Object());

    final AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(ctx);
    when(span.getSpanId()).thenReturn(1L);

    requestCallback =
        (requestContext, request) -> {
          requests.add(request);
          return Flow.ResultFlow.empty();
        };
    responseCallback =
        (requestContext, response) -> {
          responses.add(response);
          return Flow.ResultFlow.empty();
        };
    final CallbackProvider cbp = mock(CallbackProvider.class);
    when(cbp.getCallback(EVENTS.httpClientRequest())).thenAnswer(invocation -> requestCallback);
    when(cbp.getCallback(EVENTS.httpClientResponse())).thenAnswer(invocation -> responseCallback);

    tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(cbp);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void reportsRedirectResponseAndFollowUpRequest() {
    AppSecInterceptor.onRedirect(redirect(), followUp);

    assertEquals(1, responses.size());
    assertEquals(302, responses.get(0).getStatus());
    assertEquals(1, requests.size());
    assertEquals("http://example.com/json", requests.get(0).getUrl());
    assertEquals("GET", requests.get(0).getMethod());
  }

  @Test
  void ignoresRedirectWithoutActiveSpan() {
    when(tracer.activeSpan()).thenReturn(null);

    assertDoesNotThrow(() -> AppSecInterceptor.onRedirect(redirect(), followUp));

    assertTrue(requests.isEmpty());
    assertTrue(responses.isEmpty());
  }

  @Test
  void ignoresRedirectWithoutAppSecContext() {
    when(ctx.getData(RequestContextSlot.APPSEC)).thenReturn(null);

    AppSecInterceptor.onRedirect(redirect(), followUp);

    assertTrue(requests.isEmpty());
    assertTrue(responses.isEmpty());
  }

  @Test
  void swallowsNonBlockingFailures() {
    requestCallback =
        (requestContext, request) -> {
          throw new IllegalStateException("boom");
        };

    assertDoesNotThrow(() -> AppSecInterceptor.onRedirect(redirect(), followUp));
  }

  @Test
  void propagatesBlockingException() {
    requestCallback = (requestContext, request) -> blockingFlow();

    assertThrows(BlockingException.class, () -> AppSecInterceptor.onRedirect(redirect(), followUp));
  }

  @Test
  void adviceReportsRedirectWithoutClosingTheBody() {
    final FakeInterceptor interceptor = new FakeInterceptor();

    FollowUpRequestAdvice.onFollowUpRequest(interceptor, redirect(), followUp);

    assertEquals(1, responses.size());
    assertEquals(1, requests.size());
    assertFalse(bodyClosed);
    assertFalse(interceptor.allocation.released);
  }

  @Test
  void adviceIgnoresRedirectsThatAreNotFollowed() {
    FollowUpRequestAdvice.onFollowUpRequest(new FakeInterceptor(), redirect(), null);

    assertTrue(requests.isEmpty());
    assertTrue(responses.isEmpty());
  }

  @Test
  void adviceIgnoresNonRedirectFollowUps() {
    final Response unauthorized = redirect().newBuilder().code(401).build();

    FollowUpRequestAdvice.onFollowUpRequest(new FakeInterceptor(), unauthorized, followUp);

    assertTrue(requests.isEmpty());
    assertFalse(bodyClosed);
  }

  @Test
  void adviceReleasesTheRedirectWhenTheRequestIsBlocked() {
    requestCallback = (requestContext, request) -> blockingFlow();
    final FakeInterceptor interceptor = new FakeInterceptor();

    assertThrows(
        BlockingException.class,
        () -> FollowUpRequestAdvice.onFollowUpRequest(interceptor, redirect(), followUp));

    assertTrue(bodyClosed);
    assertTrue(interceptor.allocation.released);
  }

  @Test
  void adviceReleasesTheRedirectWhenTheResponseIsBlocked() {
    responseCallback = (requestContext, response) -> blockingFlow();
    final FakeInterceptor interceptor = new FakeInterceptor();

    assertThrows(
        BlockingException.class,
        () -> FollowUpRequestAdvice.onFollowUpRequest(interceptor, redirect(), followUp));

    assertTrue(requests.isEmpty());
    assertTrue(bodyClosed);
    assertTrue(interceptor.allocation.released);
  }

  @Test
  void blockedRedirectCleanupToleratesInterceptorsWithoutStreamAllocation() {
    requestCallback = (requestContext, request) -> blockingFlow();

    // OkHttp 3.14+ has no streamAllocation(): only the body is closed
    assertThrows(
        BlockingException.class,
        () -> FollowUpRequestAdvice.onFollowUpRequest(new Object(), redirect(), followUp));

    assertTrue(bodyClosed);
  }

  /** Mimics the public {@code streamAllocation()} accessor of OkHttp 3.4-3.13. */
  public static class FakeInterceptor {
    final FakeStreamAllocation allocation = new FakeStreamAllocation();

    public FakeStreamAllocation streamAllocation() {
      return allocation;
    }
  }

  public static class FakeStreamAllocation {
    boolean released;

    public void release() {
      released = true;
    }
  }

  private Response redirect() {
    final ForwardingSource source =
        new ForwardingSource(new Buffer()) {
          @Override
          public void close() throws IOException {
            bodyClosed = true;
            super.close();
          }
        };
    return new Response.Builder()
        .request(new Request.Builder().url("http://example.com/redirect").build())
        .protocol(Protocol.HTTP_1_1)
        .code(302)
        .message("Found")
        .header("Location", "/json")
        .body(ResponseBody.create(null, 0, Okio.buffer(source)))
        .build();
  }

  private static Flow<Void> blockingFlow() {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return RBA;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }
}

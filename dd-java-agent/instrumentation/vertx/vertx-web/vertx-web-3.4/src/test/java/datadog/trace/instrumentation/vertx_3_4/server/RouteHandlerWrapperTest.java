package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper.finishHandlerSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteHandlerWrapperTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void finishesSpanOnceAcrossThreads(boolean useEndHandler) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int iteration = 0; iteration < 1000; iteration++) {
        AgentSpan span = mock(AgentSpan.class);
        AtomicReference<AgentSpan> spanReference = new AtomicReference<>(span);
        RoutingContext context = mock(RoutingContext.class);
        when(context.get(HANDLER_SPAN_CONTEXT_KEY)).thenReturn(spanReference);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.response()).thenReturn(response);
        when(response.getStatusCode()).thenReturn(403);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> finishAfterStart(context, ready, start, false));
        Future<?> second =
            executor.submit(() -> finishAfterStart(context, ready, start, useEndHandler));
        try {
          assertTrue(ready.await(10, SECONDS));
        } finally {
          start.countDown();
        }
        first.get(10, SECONDS);
        second.get(10, SECONDS);

        // Later callbacks must also be harmless once either racing caller has claimed the span.
        finishHandlerSpan(context);
        verify(span).finish();
        verify(context).response();
        assertNull(spanReference.get());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void ignoresContextWithoutHandlerSpan() {
    RoutingContext context = mock(RoutingContext.class);
    finishHandlerSpan(context);
    verify(context).get(HANDLER_SPAN_CONTEXT_KEY);
    verifyNoMoreInteractions(context);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reusesExistingHandlerSpan() {
    AgentSpan span = mock(AgentSpan.class);
    AtomicReference<AgentSpan> spanReference = new AtomicReference<>(span);
    RoutingContext context = mock(RoutingContext.class);
    when(context.get(HANDLER_SPAN_CONTEXT_KEY)).thenReturn(spanReference);
    Handler<RoutingContext> handler = mock(Handler.class);

    new RouteHandlerWrapper(handler).handle(context);

    verify(handler).handle(context);
    assertSame(span, spanReference.get());
    verify(span, never()).finish();
  }

  private static void finishAfterStart(
      RoutingContext context, CountDownLatch ready, CountDownLatch start, boolean useEndHandler) {
    ready.countDown();
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    if (useEndHandler) {
      new EndHandlerWrapper(context).handle(null);
    } else {
      finishHandlerSpan(context);
    }
  }
}

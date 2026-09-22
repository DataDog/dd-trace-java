package server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Vertx;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.impl.LocalSessionStoreImpl;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalSessionStoreInstrumentationTest extends AbstractInstrumentationTest {
  private Vertx vertx;

  @BeforeEach
  void createVertx() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void closeVertx() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void creatingSessionStoreDoesNotRetainRequest() throws Exception {
    LocalSessionStore store;
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      store = LocalSessionStore.create(vertx, "creation", 60_000);
      assertSame(parent, activeSpan());
      assertTrue(isAsyncPropagationEnabled());
    } finally {
      parent.finish();
    }
    try {
      assertParentReported();
    } finally {
      store.close();
    }
  }

  @Test
  void rearmingSessionReaperDoesNotRetainRequest() throws Exception {
    LocalSessionStore store = LocalSessionStore.create(vertx, "rearming", 60_000);
    try {
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        // A worker queue can invoke the maintenance callback under an unrelated request context.
        ((LocalSessionStoreImpl) store).handle(0L);
        assertSame(parent, activeSpan());
        assertTrue(isAsyncPropagationEnabled());
      } finally {
        parent.finish();
      }
      assertParentReported();
    } finally {
      store.close();
    }
  }

  @Test
  void failedSchedulingRestoresPropagation() throws Exception {
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> LocalSessionStore.create(vertx, "invalid-interval", -1));
      assertSame(parent, activeSpan());
      assertTrue(isAsyncPropagationEnabled());
    } finally {
      parent.finish();
    }
    assertParentReported();
  }

  @Test
  void preservesDisabledPropagation() throws Exception {
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      setAsyncPropagationEnabled(false);
      LocalSessionStore store = LocalSessionStore.create(vertx, "disabled", 60_000);
      try {
        assertFalse(isAsyncPropagationEnabled());
      } finally {
        store.close();
      }
    } finally {
      parent.finish();
    }
    assertParentReported();
  }

  @Test
  void applicationTimerStillPropagatesRequest() throws Exception {
    CompletableFuture<AgentSpan> observed = new CompletableFuture<>();
    AgentSpan parent = startSpan("test", "parent");
    try (ContextScope ignored = activateSpan(parent)) {
      vertx.setTimer(10, id -> observed.complete(activeSpan()));
    } finally {
      parent.finish();
    }
    assertSame(parent, observed.get(10, TimeUnit.SECONDS));
    assertParentReported();
  }

  private void assertParentReported() throws Exception {
    // Assert before closing the store: cancellation would otherwise conceal a captured
    // continuation.
    assertTrue(writer.waitForTracesMax(1, 3), "Session maintenance retained the request trace");
    assertTraces(trace(span().root().operationName("parent")));
  }
}

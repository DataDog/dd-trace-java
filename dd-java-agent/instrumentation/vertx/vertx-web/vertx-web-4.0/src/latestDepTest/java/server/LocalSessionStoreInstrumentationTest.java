package server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Vertx;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.impl.LocalSessionStoreImpl;
import org.junit.jupiter.api.Test;

class LocalSessionStoreInstrumentationTest extends AbstractInstrumentationTest {
  @Test
  void initializingSessionStoreDoesNotRetainRequest() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      AgentSpan parent = startSpan("test", "parent");
      LocalSessionStore store;
      try (ContextScope ignored = activateSpan(parent)) {
        store = LocalSessionStore.create(vertx, "initializing", 60_000);
      } finally {
        parent.finish();
      }
      try {
        assertTrue(writer.waitForTracesMax(1, 3), "Session-store timers retained the request trace");
        assertTraces(trace(span().root().operationName("parent")));
      } finally {
        store.close();
      }
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, SECONDS);
    }
  }

  @Test
  void rearmingSessionReaperDoesNotRetainRequest() throws Exception {
    Vertx vertx = Vertx.vertx();
    LocalSessionStore store = LocalSessionStore.create(vertx, "rearming", 60_000);
    try {
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        ((LocalSessionStoreImpl) store).handle(0L);
      } finally {
        parent.finish();
      }
      assertTrue(writer.waitForTracesMax(1, 3), "Session reaper retained the request trace");
      assertTraces(trace(span().root().operationName("parent")));
    } finally {
      store.close();
      vertx.close().toCompletionStage().toCompletableFuture().get(10, SECONDS);
    }
  }
}

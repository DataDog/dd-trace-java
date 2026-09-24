package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static testdog.trace.instrumentation.java.lang.jdk21.PollerContextForkedTest.field;
import static testdog.trace.instrumentation.java.lang.jdk21.PollerContextForkedTest.virtualThreadState;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class HttpClientSelectorContextForkedTest extends AbstractInstrumentationTest {
  @Test
  void selectorDoesNotRetainCreatingRequest() throws Exception {
    assumeTrue(Runtime.version().feature() >= 26);
    HttpClient client = null;
    AgentSpan parent = startSpan("test", "client-owner");
    try {
      try (ContextScope ignored = activateSpan(parent)) {
        client = HttpClient.newHttpClient();
        Object impl = field(client.getClass(), "impl").get(client);
        Thread selector = (Thread) field(impl.getClass(), "selmgrThread").get(impl);
        assertTrue(selector.isVirtual());
        assertTrue(selector.isAlive());
        assertNull(virtualThreadState(selector), "live selector must not retain request context");
        assertSame(parent, activeSpan());
      } finally {
        parent.finish();
      }
      // Publish while the client is still alive: closing it must not be needed to release parent.
      assertTraces(trace(span().root().operationName("client-owner")));
    } finally {
      if (client != null) {
        client.close();
      }
    }
  }
}

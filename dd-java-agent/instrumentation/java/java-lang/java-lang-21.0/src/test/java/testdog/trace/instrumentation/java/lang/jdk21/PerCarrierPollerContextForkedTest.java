package testdog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static testdog.trace.instrumentation.java.lang.jdk21.PollerContextForkedTest.field;
import static testdog.trace.instrumentation.java.lang.jdk21.PollerContextForkedTest.virtualThreadState;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.util.PollingConditions;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX, OS.MAC})
class PerCarrierPollerContextForkedTest extends AbstractInstrumentationTest {
  @Test
  void firstReadOnCarrierDoesNotRetainRequest() throws Exception {
    assumeTrue(Runtime.version().feature() >= 27);
    // A separate fork is needed: poller mode is fixed by Poller.<clinit>.
    System.setProperty("jdk.pollerMode", "3");
    System.setProperty("jdk.writePollers", "1");
    assertFalse(GlobalIgnores.isIgnored("sun.nio.ch.Poller$PollerPerCarrierPollerGroup", false));

    AgentSpan parent = startSpan("test", "carrier-poller-owner");
    Object parentSpan = parent;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader = null;
    try (ContextScope ignored = activateSpan(parent)) {
      Class<?> poller = Class.forName("sun.nio.ch.Poller");
      Object group = field(poller, "POLLER_GROUP").get(null);
      @SuppressWarnings("unchecked")
      Set<Object> readPollers = (Set<Object>) field(group.getClass(), "readPollers").get(group);
      assertTrue(readPollers.isEmpty(), "read pollers must be created lazily by I/O");

      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
          Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
          Socket peer = server.accept()) {
        socket.setSoTimeout(10_000);
        reader =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        assertSame(parentSpan, activeSpan());
                        assertEquals(1, socket.getInputStream().read());
                        assertSame(
                            parentSpan,
                            activeSpan(),
                            "poller creation must restore request context");
                      } catch (Throwable error) {
                        failure.set(error);
                      }
                    });

        new PollingConditions(10)
            .eventually(
                () -> {
                  assertFalse(readPollers.isEmpty());
                  for (Object readPoller : readPollers) {
                    Thread owner = (Thread) field(poller, "owner").get(readPoller);
                    assertTrue(owner != null && owner.isAlive());
                    assertTrue(owner.isVirtual());
                    assertNull(
                        virtualThreadState(owner),
                        "live carrier poller must retain neither context nor continuation");
                  }
                });
        peer.getOutputStream().write(1);
        assertTrue(reader.join(Duration.ofSeconds(10)));
        assertNull(failure.get());
        assertSame(parent, activeSpan());
      }
    } finally {
      // Closing the sockets also releases the reader if an assertion above fails.
      if (reader != null) {
        reader.join(Duration.ofSeconds(10));
      }
      parent.finish();
    }
    assertTraces(trace(span().root().operationName("carrier-poller-owner")));
  }
}

package datadog.trace.api;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

// both tests register a global tracer, which can only happen once per JVM: keep Spock's order
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WithGlobalTracerForkedTest {

  @Test
  @Order(1)
  void shouldCallTheCallbackAtTheRightTime() {
    TracerAPI tracer = mock(TracerAPI.class);
    ConcurrentLinkedQueue<TracerAPI> queue = new ConcurrentLinkedQueue<>();
    WithGlobalTracer.Callback callback = queue::add;

    WithGlobalTracer.registerOrExecute(callback);
    assertTrue(queue.isEmpty());

    WithGlobalTracer.registerOrExecute(callback);
    assertTrue(queue.isEmpty());

    GlobalTracer.registerIfAbsent(tracer);
    assertEquals(asList(tracer, tracer), new ArrayList<>(queue));

    WithGlobalTracer.registerOrExecute(callback);
    assertEquals(asList(tracer, tracer, tracer), new ArrayList<>(queue));
  }

  @Test
  @Order(2)
  void shouldNotCrashOnUnsupportedTracerType() {
    Tracer tracer = mock(Tracer.class);
    WithGlobalTracer.Callback callback = ignored -> {};

    assertDoesNotThrow(
        () -> {
          WithGlobalTracer.registerOrExecute(callback);
          GlobalTracer.registerIfAbsent(tracer);
        });
  }
}

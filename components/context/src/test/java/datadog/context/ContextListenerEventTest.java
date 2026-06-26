package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextListenerEventTest extends ContextTestBase {
  @Test
  void testListenersNotifiedOnAttachAndDetach() {
    List<String> events = new ArrayList<>();
    ContextManager.register(keyedTrackingListener(events, STRING_KEY));
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      assertEquals(asList("attach:value"), events);
    }
    assertEquals(asList("attach:value", "detach:value"), events);
  }

  @Test
  void testListenersNotNotifiedForRootContext() {
    List<String> events = new ArrayList<>();
    ContextManager.register(trackingListener(events));
    root().attach(); // current is already root, no events
    assertTrue(events.isEmpty(), "root attach should not trigger listeners");
    root().swap(); // current is already root, no events
    assertTrue(events.isEmpty(), "root swap should not trigger listeners");
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      assertEquals(1, events.size()); // attach:non-root only
    }
    assertEquals(2, events.size()); // detach:non-root but not attach:root
  }

  @Test
  void testListenersNotNotifiedOnSameContextAttach() {
    List<String> events = new ArrayList<>();
    ContextManager.register(trackingListener(events));
    Context context = root().with(STRING_KEY, "same");
    try (ContextScope outer = context.attach()) {
      assertEquals(asList("attach"), events);
      try (ContextScope noop = context.attach()) {
        assertEquals(context, current());
        assertEquals(asList("attach"), events); // no new events on same-context attach
      }
      assertEquals(asList("attach"), events); // noop close fires no events either
    }
    assertEquals(asList("attach", "detach"), events);
  }

  @Test
  void testListenersNotNotifiedOnSameContextSwap() {
    List<String> events = new ArrayList<>();
    ContextManager.register(trackingListener(events));
    Context context = root().with(STRING_KEY, "same");
    context.swap();
    assertEquals(asList("attach"), events);
    context.swap(); // same context again, no events
    assertEquals(asList("attach"), events);
    root().swap();
    assertEquals(asList("attach", "detach"), events);
  }

  @Test
  void testDuplicateListenerIgnored() {
    List<String> events = new ArrayList<>();
    ContextListener listener = trackingListener(events);
    ContextManager.register(listener);
    ContextManager.register(listener); // should be ignored
    try (ContextScope scope = root().with(STRING_KEY, "value").attach()) {}
    assertEquals(asList("attach", "detach"), events);
  }

  @Test
  void testMultipleListenersAllNotified() {
    List<String> events1 = new ArrayList<>();
    List<String> events2 = new ArrayList<>();
    ContextManager.register(trackingListener(events1));
    ContextManager.register(trackingListener(events2));
    try (ContextScope scope = root().with(STRING_KEY, "value").attach()) {}
    assertEquals(asList("attach", "detach"), events1);
    assertEquals(asList("attach", "detach"), events2);
  }

  @Test
  void testSwapNotifiesListeners() {
    List<String> events = new ArrayList<>();
    ContextManager.register(keyedTrackingListener(events, STRING_KEY));
    Context context = root().with(STRING_KEY, "value");
    Context previous = context.swap();
    assertSame(root(), previous);
    assertEquals(asList("attach:value"), events);
    previous = root().swap();
    assertSame(context, previous);
    assertEquals(asList("attach:value", "detach:value"), events);
  }
}

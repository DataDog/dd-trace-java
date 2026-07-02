package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ContextListenerEventTest extends ContextTestBase {
  @Test
  void testListenersNotifiedOnAttachAndDetach() {
    TrackingListener listener = keyedTrackingListener(STRING_KEY);
    ContextManager.register(listener);
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      listener.assertNewEvents("attach:value");
    }
    listener.assertNewEvents("detach:value");
  }

  @Test
  void testListenersNotNotifiedForRootContext() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    root().attach(); // current is already root, no events
    listener.assertNoEvents(); // root attach should not trigger listeners
    root().swap(); // current is already root, no events
    listener.assertNoEvents(); // root swap should not trigger listeners
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      listener.assertNewEvents("attach"); // attach:non-root only
    }
    listener.assertNewEvents("detach"); // detach:non-root but not attach:root
  }

  @Test
  void testListenersNotNotifiedOnSameContextAttach() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(STRING_KEY, "same");
    try (ContextScope outer = context.attach()) {
      listener.assertNewEvents("attach");
      try (ContextScope noop = context.attach()) {
        assertEquals(context, current());
        listener.assertNoNewEvents(); // no new events on same-context attach
      }
      listener.assertNoNewEvents(); // noop close fires no events either
    }
    listener.assertNewEvents("detach");
  }

  @Test
  void testListenersNotNotifiedOnSameContextSwap() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(STRING_KEY, "same");
    context.swap();
    listener.assertNewEvents("attach");
    context.swap(); // same context again, no events
    listener.assertNoNewEvents();
    root().swap();
    listener.assertNewEvents("detach");
  }

  @Test
  void testDuplicateListenerIgnored() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    ContextManager.register(listener); // should be ignored
    try (ContextScope scope = root().with(STRING_KEY, "value").attach()) {}
    listener.assertEvents("attach", "detach");
  }

  @Test
  void testMultipleListenersAllNotified() {
    TrackingListener listener1 = trackingListener();
    TrackingListener listener2 = trackingListener();
    ContextManager.register(listener1);
    ContextManager.register(listener2);
    try (ContextScope scope = root().with(STRING_KEY, "value").attach()) {}
    listener1.assertEvents("attach", "detach");
    listener2.assertEvents("attach", "detach");
  }

  @Test
  void testSwapNotifiesListeners() {
    TrackingListener listener = keyedTrackingListener(STRING_KEY);
    ContextManager.register(listener);
    Context context = root().with(STRING_KEY, "value");
    Context previous = context.swap();
    assertSame(root(), previous);
    listener.assertNewEvents("attach:value");
    previous = root().swap();
    assertSame(context, previous);
    listener.assertNewEvents("detach:value");
  }
}

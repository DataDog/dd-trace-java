package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

class ContextListenerEventTest extends ContextTestBase {
  @Test
  void testListenersNotifiedOnAttachAndDetach() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(TEST_KEY, "value");
    try (ContextScope scope = context.attach()) {
      listener.assertNewEvents("update:{root}->value");
    }
    listener.assertNewEvents("update:value->{root}");
  }

  @Test
  void testListenersNotNotifiedForSameContextAttachOrSwap() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    // current is already root, no events
    root().attach();
    listener.assertNoEvents();
    // current is already root, no events
    root().swap();
    listener.assertNoEvents();
    Context context = root().with(TEST_KEY, "value");
    try (ContextScope scope = context.attach()) {
      listener.assertNewEvents("update:{root}->value");
    }
    listener.assertNewEvents("update:value->{root}");
  }

  @Test
  void testListenersNotNotifiedOnSameContextAttach() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(TEST_KEY, "same");
    try (ContextScope outer = context.attach()) {
      listener.assertNewEvents("update:{root}->same");
      try (ContextScope noop = context.attach()) {
        assertEquals(context, current());
        // no new events on same-context attach
        listener.assertNoNewEvents();
      }
      // noop close fires no events either
      listener.assertNoNewEvents();
    }
    listener.assertNewEvents("update:same->{root}");
  }

  @Test
  void testListenersNotNotifiedOnSameContextSwap() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(TEST_KEY, "same");
    context.swap();
    listener.assertNewEvents("update:{root}->same");
    // same context again, no events
    context.swap();
    listener.assertNoNewEvents();
    root().swap();
    listener.assertNewEvents("update:same->{root}");
  }

  @Test
  void testDuplicateListenerIgnored() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    // should be ignored
    ContextManager.register(listener);
    try (ContextScope scope = root().with(TEST_KEY, "value").attach()) {
    }
    listener.assertEvents("update:{root}->value", "update:value->{root}");
  }

  @Test
  void testMultipleListenersAllNotified() {
    TrackingListener listener1 = trackingListener();
    TrackingListener listener2 = trackingListener();
    ContextManager.register(listener1);
    ContextManager.register(listener2);
    try (ContextScope scope = root().with(TEST_KEY, "value").attach()) {
    }
    listener1.assertEvents("update:{root}->value", "update:value->{root}");
    listener2.assertEvents("update:{root}->value", "update:value->{root}");
  }

  @Test
  void testSwapNotifiesListeners() {
    TrackingListener listener = trackingListener();
    ContextManager.register(listener);
    Context context = root().with(TEST_KEY, "value");
    Context previous = context.swap();
    assertSame(root(), previous);
    listener.assertNewEvents("update:{root}->value");
    previous = root().swap();
    assertSame(context, previous);
    listener.assertNewEvents("update:value->{root}");
  }
}

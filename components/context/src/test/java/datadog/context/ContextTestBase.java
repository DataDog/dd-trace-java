package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@ParametersAreNonnullByDefault
abstract class ContextTestBase {
  static final ContextKey<String> TEST_KEY = ContextKey.named("test-key");

  @BeforeEach
  void verifyNoContextBefore() {
    assertEquals(root(), current());
  }

  @AfterEach
  void verifyNoContextAfter() {
    TestContextManager.clearListeners();
    assertEquals(root(), current());
  }

  static TrackingListener trackingListener() {
    return new TrackingListener();
  }

  /**
   * A {@link ContextListener} that records events suffixed with {@link #TEST_KEY} context values.
   * Uses {@code {root}} when the key is absent from the context.
   */
  static final class TrackingListener implements ContextListener {
    private final List<String> events = new ArrayList<>();
    private int checkpoint;

    @Override
    public void onUpdate(Context before, Context after) {
      this.events.add("update:" + label(before) + "->" + label(after));
    }

    @Override
    public void onCapture(Context context) {
      this.events.add("capture:" + label(context));
    }

    @Override
    public void onRelease(Context context) {
      this.events.add("release:" + label(context));
    }

    private static String label(Context context) {
      String value = context.get(TEST_KEY);
      return value != null ? value : "{root}";
    }

    /** Asserts the full sequence of recorded events equals {@code expected}. */
    void assertEvents(String... expected) {
      assertEquals(asList(expected), new ArrayList<>(this.events));
    }

    /** Asserts that no events have been recorded at all. */
    void assertNoEvents() {
      assertEvents();
    }

    /**
     * Asserts the events recorded since the previous {@link #assertNewEvents} call equal {@code
     * expected}, then advances the checkpoint past them.
     */
    void assertNewEvents(String... expected) {
      List<String> snapshot = new ArrayList<>(this.events);
      List<String> newEvents = new ArrayList<>(snapshot.subList(this.checkpoint, snapshot.size()));
      assertEquals(asList(expected), newEvents);
      this.checkpoint = snapshot.size();
    }

    /**
     * Asserts that no events have been recorded since the previous {@link #assertNewEvents} or
     * {@link #assertNoNewEvents} call.
     */
    void assertNoNewEvents() {
      assertNewEvents();
    }
  }
}

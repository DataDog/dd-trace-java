package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@ParametersAreNonnullByDefault
abstract class ContextTestBase {
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
    return new TrackingListener(null);
  }

  static TrackingListener keyedTrackingListener(ContextKey<String> key) {
    return new TrackingListener(key);
  }

  /**
   * A {@link ContextListener} that records the events it receives so tests can assert on them.
   *
   * <p>With a {@link ContextKey}, each event is suffixed with that key's context value; otherwise
   * only the event name is recorded (e.g. {@code "attach"}).
   */
  static final class TrackingListener implements ContextListener {
    private final List<String> events;
    @Nullable private final ContextKey<String> key;
    private int checkpoint;

    private TrackingListener(@Nullable ContextKey<String> key) {
      this.events = new ArrayList<>();
      this.key = key;
    }

    @Override
    public void onAttach(Context context) {
      record("attach", context);
    }

    @Override
    public void onDetach(Context context) {
      record("detach", context);
    }

    @Override
    public void onCapture(Context context) {
      record("capture", context);
    }

    @Override
    public void onRelease(Context context) {
      record("release", context);
    }

    private void record(String event, Context context) {
      this.events.add(this.key == null ? event : event + ":" + context.get(this.key));
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

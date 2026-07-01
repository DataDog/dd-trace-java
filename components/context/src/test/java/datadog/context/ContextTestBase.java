package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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

  static ContextListener trackingListener(List<String> events) {
    return new ContextListener() {
      @Override
      public void onAttach(Context c) {
        events.add("attach");
      }

      @Override
      public void onDetach(Context c) {
        events.add("detach");
      }

      @Override
      public void onCapture(Context c) {
        events.add("capture");
      }

      @Override
      public void onRelease(Context c) {
        events.add("release");
      }
    };
  }

  static ContextListener keyedTrackingListener(List<String> events, ContextKey<String> key) {
    return new ContextListener() {
      @Override
      public void onAttach(Context c) {
        events.add("attach:" + c.get(key));
      }

      @Override
      public void onDetach(Context c) {
        events.add("detach:" + c.get(key));
      }

      @Override
      public void onCapture(Context c) {
        events.add("capture:" + c.get(key));
      }

      @Override
      public void onRelease(Context c) {
        events.add("release:" + c.get(key));
      }
    };
  }
}

package datadog.trace.api.civisibility;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class TestEventsBridge {

  public interface TestEventsListener {
    void onTestModuleStarted();

    void onTestModuleFinished();
  }

  private static final List<TestEventsListener> LISTENERS = new CopyOnWriteArrayList<>();

  public static void addListener(TestEventsListener listener) {
    LISTENERS.add(listener);
  }

  public static void onTestModuleStarted() {
    for (TestEventsListener listener : LISTENERS) {
      listener.onTestModuleStarted();
    }
  }

  public static void onTestModuleFinished() {
    for (TestEventsListener listener : LISTENERS) {
      listener.onTestModuleFinished();
    }
  }
}

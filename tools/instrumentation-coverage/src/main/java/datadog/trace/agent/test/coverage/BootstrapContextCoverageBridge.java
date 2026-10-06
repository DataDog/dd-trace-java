package datadog.trace.agent.test.coverage;

/** Bootstrap-visible bridge used only when the coverage collector observes JDK classes. */
public final class BootstrapContextCoverageBridge {
  /** Implemented by the application-loader collector after this class is loaded by bootstrap. */
  public interface Listener {
    void onEntry(String method);
  }

  private static final ThreadLocal<Boolean> RECORDING = new ThreadLocal<>();
  private static volatile Listener listener;

  private BootstrapContextCoverageBridge() {}

  public static void install(Listener candidate) {
    if (listener != null) {
      throw new IllegalStateException("Bootstrap Context coverage listener is already installed");
    }
    listener = candidate;
  }

  public static void clear(Listener candidate) {
    if (listener == candidate) {
      listener = null;
    }
  }

  public static void observeEntry(String method) {
    Listener current = listener;
    if (current == null || Boolean.TRUE.equals(RECORDING.get())) {
      return;
    }
    RECORDING.set(Boolean.TRUE);
    try {
      current.onEntry(method);
    } finally {
      RECORDING.remove();
    }
  }
}

package datadog.trace.core.monitor;

// Abstract health metrics class for RUM injector
public abstract class RumInjectorHealthMetrics implements AutoCloseable {
  public static RumInjectorHealthMetrics NO_OP = new RumInjectorHealthMetrics() {};

  public void start() {}

  public void onInjectionSucceed() {}

  public void onInjectionFailed() {}

  public void onInjectionSkipped() {}

  /** @return Human-readable summary of the current health metrics. */
  public String summary() {
    return "";
  }

  @Override
  public void close() {}
}

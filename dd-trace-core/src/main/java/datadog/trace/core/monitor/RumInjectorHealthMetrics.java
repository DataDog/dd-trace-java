package datadog.trace.core.monitor;

// Abstract health metrics for RUM injector
// This class defines the interface for monitoring RUM injection operations
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

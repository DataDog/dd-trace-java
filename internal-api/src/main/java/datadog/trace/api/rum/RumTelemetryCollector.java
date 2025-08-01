package datadog.trace.api.rum;

// Collect RUM injection telemetry from the RumInjector
// This is implemented by the RumInjectorMetrics class
public interface RumTelemetryCollector {

  RumTelemetryCollector NO_OP =
      new RumTelemetryCollector() {
        @Override
        public void start() {}

        @Override
        public void onInjectionSucceed() {}

        @Override
        public void onInjectionFailed() {}

        @Override
        public void onInjectionSkipped() {}

        @Override
        public void onContentSecurityPolicyDetected() {}

        @Override
        public void onInjectionResponseSize(long bytes) {}

        @Override
        public void close() {}

        @Override
        public String summary() {
          return "";
        }
      };

  default void start() {}

  // call when RUM injection succeeds
  void onInjectionSucceed();

  // call when RUM injection fails
  void onInjectionFailed();

  // call when RUM injection is skipped
  void onInjectionSkipped();

  // call when a Content Security Policy header is detected
  void onContentSecurityPolicyDetected();

  // call to get the response size (in bytes) before RUM injection
  void onInjectionResponseSize(long bytes);

  default void close() {}

  // human-readable summary of the current health metrics
  default String summary() {
    return "";
  }
}

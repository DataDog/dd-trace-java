package datadog.trace.api.rum;

// Collect RUM injection telemetry from the RumInjector
// This is implemented by the RumInjectorMetrics class
public interface RumTelemetryCollector {

  RumTelemetryCollector NO_OP =
      new RumTelemetryCollector() {
        @Override
        public void onInjectionSucceed(String integrationVersion) {}

        @Override
        public void onInjectionFailed(String integrationVersion, String contentEncoding) {}

        @Override
        public void onInjectionSkipped(String integrationVersion) {}

        @Override
        public void onInitializationSucceed() {}

        @Override
        public void onContentSecurityPolicyDetected(String integrationVersion) {}

        @Override
        public void onInjectionResponseSize(String integrationVersion, long bytes) {}

        @Override
        public void onInjectionTime(String integrationVersion, long milliseconds) {}

        @Override
        public void close() {}

        @Override
        public String summary() {
          return "";
        }
      };

  // call when RUM injection succeeds
  void onInjectionSucceed(String integrationVersion);

  // call when RUM injection fails
  void onInjectionFailed(String integrationVersion, String contentEncoding);

  // call when RUM injection is skipped
  void onInjectionSkipped(String integrationVersion);

  // call when RUM injector initialization succeeds
  void onInitializationSucceed();

  // call when a Content Security Policy header is detected
  void onContentSecurityPolicyDetected(String integrationVersion);

  // call to get the response size before RUM injection
  void onInjectionResponseSize(String integrationVersion, long bytes);

  // call to report the time it takes to inject the RUM SDK
  void onInjectionTime(String integrationVersion, long milliseconds);

  default void close() {}

  // human-readable summary of the current health metrics
  default String summary() {
    return "";
  }
}

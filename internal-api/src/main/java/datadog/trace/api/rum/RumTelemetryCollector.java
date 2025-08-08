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

  void onInjectionSucceed(String integrationVersion);

  void onInjectionFailed(String integrationVersion, String contentEncoding);

  void onInjectionSkipped(String integrationVersion);

  void onInitializationSucceed();

  void onContentSecurityPolicyDetected(String integrationVersion);

  void onInjectionResponseSize(String integrationVersion, long bytes);

  void onInjectionTime(String integrationVersion, long milliseconds);

  default void close() {}

  default String summary() {
    return "";
  }
}

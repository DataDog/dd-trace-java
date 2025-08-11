package datadog.trace.api.rum;

/**
 * Collect RUM injection telemetry from the RumInjector This is implemented by the
 * RumInjectorMetrics class
 */
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

  /**
   * Reports successful RUM injection.
   *
   * @param integrationVersion The version of the integration that was injected.
   */
  void onInjectionSucceed(String integrationVersion);

  /**
   * Reports failed RUM injection.
   *
   * @param integrationVersion The version of the integration that was injected.
   * @param contentEncoding The content encoding of the response that was injected.
   */
  void onInjectionFailed(String integrationVersion, String contentEncoding);

  /**
   * Reports skipped RUM injection.
   *
   * @param integrationVersion The version of the integration that was injected.
   */
  void onInjectionSkipped(String integrationVersion);

  /** Reports successful RUM injector initialization. */
  void onInitializationSucceed();

  /**
   * Reports content security policy detected in the response header to be injected.
   *
   * @param integrationVersion The version of the integration that was injected.
   */
  void onContentSecurityPolicyDetected(String integrationVersion);

  /**
   * Reports the size of the response before injection.
   *
   * @param integrationVersion The version of the integration that was injected.
   * @param bytes The size of the response before injection.
   */
  void onInjectionResponseSize(String integrationVersion, long bytes);

  /**
   * Reports the time taken to inject the RUM SDK.
   *
   * @param integrationVersion The version of the integration that was injected.
   * @param milliseconds The time taken to inject the RUM SDK.
   */
  void onInjectionTime(String integrationVersion, long milliseconds);

  /** Closes the telemetry collector. */
  default void close() {}

  /** Returns a human-readable summary of the telemetry collected. */
  default String summary() {
    return "";
  }
}

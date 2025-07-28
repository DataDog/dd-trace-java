package datadog.trace.api.rum;

// Collect RUM injection telemetry
public interface RumTelemetryCollector {

  RumTelemetryCollector NO_OP =
      new RumTelemetryCollector() {
        @Override
        public void onInjectionSucceed() {}

        @Override
        public void onInjectionFailed() {}

        @Override
        public void onInjectionSkipped() {}
      };

  // call when RUM injection succeeds
  void onInjectionSucceed();

  // call when RUM injection fails
  void onInjectionFailed();

  // call when RUM injection is skipped
  void onInjectionSkipped();
}

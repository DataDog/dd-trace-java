package datadog.trace.bootstrap;

/**
 * Telemetry class used to relay information about tracer activation.
 * 
 */
final class InitializationTelemetry {
  void onInitializationError(String type) {}
  
  void onInitializationError(Throwable t) {}
  
  void send(String json) {}
}

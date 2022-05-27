package datadog.telemetry;

import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.Metric;
import java.util.Map;
import okhttp3.Request;

public interface TelemetryService {

  // Special telemetry requests
  void addStartedRequest();

  Request appClosingRequest();

  // Data for periodic telemetry requests
  boolean addConfiguration(Map<String, Object> configuration);

  boolean addDependency(Dependency dependency);

  boolean addIntegration(Integration integration);

  boolean addMetric(Metric metric);
}

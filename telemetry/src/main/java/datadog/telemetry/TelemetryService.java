package datadog.telemetry;

import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.Metric;
import java.util.Map;
import java.util.Queue;

public interface TelemetryService {

  // Special telemetry requests
  void addStartedRequest();

  TelemetryData appClosingRequest();

  // Data for periodic telemetry requests
  boolean addConfiguration(Map<String, Object> configuration);

  boolean addDependency(Dependency dependency);

  boolean addIntegration(Integration integration);

  boolean addMetric(Metric metric);

  Queue<TelemetryData> prepareRequests();

  int getHeartbeatInterval();
}

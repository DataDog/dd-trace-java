package datadog.trace.api.intake;

import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import javax.annotation.Nullable;

public enum TrackType {
  CITESTCYCLE(Endpoint.TEST_CYCLE),
  CITESTCOV(Endpoint.CODE_COVERAGE),
  LLMOBS(Endpoint.LLMOBS),
  NOOP(null);

  @Nullable public final Endpoint endpoint;

  TrackType(Endpoint endpoint) {
    this.endpoint = endpoint;
  }
}

package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import lombok.Data;
import lombok.NonNull;

@Data
public class TestState {
  @NonNull public AgentSpan testSpan;
  public AgentScope testScope;
}

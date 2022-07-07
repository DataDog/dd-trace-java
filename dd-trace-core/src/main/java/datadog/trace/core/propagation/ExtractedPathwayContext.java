package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.Map;

public class ExtractedPathwayContext implements AgentSpan.Context.Extracted {
  private final PathwayContext pathwayContext;

  public ExtractedPathwayContext(PathwayContext pathwayContext) {
    this.pathwayContext = pathwayContext;
  }

  @Override
  public DDId getTraceId() {
    return null;
  }

  @Override
  public DDId getSpanId() {
    return null;
  }

  @Override
  public AgentTrace getTrace() {
    return null;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return null;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return pathwayContext;
  }

  @Override
  public String getForwarded() {
    return null;
  }

  @Override
  public String getForwardedProto() {
    return null;
  }

  @Override
  public String getForwardedHost() {
    return null;
  }

  @Override
  public String getForwardedIp() {
    return null;
  }

  @Override
  public String getForwardedPort() {
    return null;
  }
}

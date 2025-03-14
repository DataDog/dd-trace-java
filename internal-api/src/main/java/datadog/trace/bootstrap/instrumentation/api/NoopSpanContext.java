package datadog.trace.bootstrap.instrumentation.api;

import static java.util.Collections.emptyList;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.List;
import java.util.Map;

class NoopSpanContext implements AgentSpanContext.Extracted {
  static final NoopSpanContext INSTANCE = new NoopSpanContext();

  NoopSpanContext() {}

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return emptyList();
  }

  @Override
  public PathwayContext getPathwayContext() {
    return NoopPathwayContext.INSTANCE;
  }

  @Override
  public boolean isRemote() {
    return false;
  }

  @Override
  public List<AgentSpanLink> getTerminatedContextLinks() {
    return emptyList();
  }

  @Override
  public String getForwarded() {
    return null;
  }

  @Override
  public String getFastlyClientIp() {
    return null;
  }

  @Override
  public String getCfConnectingIp() {
    return null;
  }

  @Override
  public String getCfConnectingIpv6() {
    return null;
  }

  @Override
  public String getXForwardedProto() {
    return null;
  }

  @Override
  public String getXForwardedHost() {
    return null;
  }

  @Override
  public String getXForwardedPort() {
    return null;
  }

  @Override
  public String getForwardedFor() {
    return null;
  }

  @Override
  public String getXForwardedFor() {
    return null;
  }

  @Override
  public String getXClusterClientIp() {
    return null;
  }

  @Override
  public String getXRealIp() {
    return null;
  }

  @Override
  public String getXClientIp() {
    return null;
  }

  @Override
  public String getUserAgent() {
    return null;
  }

  @Override
  public String getTrueClientIp() {
    return null;
  }

  @Override
  public String getCustomIpHeader() {
    return null;
  }
}

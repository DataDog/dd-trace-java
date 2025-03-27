package datadog.trace.bootstrap.instrumentation.api;

import static java.util.Collections.emptyMap;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.Map;

class NoopSpan extends ImmutableSpan implements AgentSpan {
  static final NoopSpan INSTANCE = new NoopSpan();

  NoopSpan() {}

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public RequestBlockingAction getRequestBlockingAction() {
    return null;
  }

  @Override
  public boolean isError() {
    return false;
  }

  @Override
  public Object getTag(final String key) {
    return null;
  }

  @Override
  public long getStartTime() {
    return 0;
  }

  @Override
  public long getDurationNano() {
    return 0;
  }

  @Override
  public String getOperationName() {
    return null;
  }

  @Override
  public String getServiceName() {
    return null;
  }

  @Override
  public CharSequence getResourceName() {
    return null;
  }

  @Override
  public RequestContext getRequestContext() {
    return RequestContext.Noop.INSTANCE;
  }

  @Override
  public Integer getSamplingPriority() {
    return (int) PrioritySampling.UNSET;
  }

  @Override
  public String getSpanType() {
    return null;
  }

  @Override
  public Map<String, Object> getTags() {
    return emptyMap();
  }

  @Override
  public AgentSpan getRootSpan() {
    return this;
  }

  @Override
  public short getHttpStatusCode() {
    return 0;
  }

  @Override
  public AgentSpan getLocalRootSpan() {
    return this;
  }

  @Override
  public boolean isSameTrace(final AgentSpan otherSpan) {
    return otherSpan == INSTANCE;
  }

  @Override
  public AgentSpanContext context() {
    return NoopSpanContext.INSTANCE;
  }

  @Override
  public String getBaggageItem(final String key) {
    return null;
  }

  @Override
  public String getSpanName() {
    return "";
  }

  @Override
  public boolean hasResourceName() {
    return false;
  }

  @Override
  public byte getResourceNamePriority() {
    return Byte.MAX_VALUE;
  }

  @Override
  public TraceConfig traceConfig() {
    return AgentTracer.NoopTraceConfig.INSTANCE;
  }

  @Override
  public boolean isOutbound() {
    return false;
  }
}

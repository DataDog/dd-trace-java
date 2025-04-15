package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.RequestContext;
import java.util.Map;

/**
 * An {@link AgentSpan} implementation that represents a remote span from an extracted span context.
 *
 * <p>Tags and baggage access are inefficient and only supported as remediation for products storing
 * propagated information into span context, until they migrate to the new context API.
 */
class ExtractedSpan extends ImmutableSpan {
  private final AgentSpanContext spanContext;

  ExtractedSpan(AgentSpanContext spanContext) {
    this.spanContext = spanContext;
  }

  @Override
  public DDTraceId getTraceId() {
    return this.spanContext.getTraceId();
  }

  @Override
  public long getSpanId() {
    return this.spanContext.getSpanId();
  }

  @Override
  public AgentSpan getRootSpan() {
    return this;
  }

  @Override
  public AgentSpan getLocalRootSpan() {
    return this;
  }

  @Override
  public boolean isError() {
    return false;
  }

  @Override
  public short getHttpStatusCode() {
    return 0;
  }

  @Override
  public CharSequence getSpanName() {
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
  public long getStartTime() {
    return 0;
  }

  @Override
  public long getDurationNano() {
    return 0;
  }

  @Override
  public CharSequence getOperationName() {
    return null;
  }

  @Override
  public String getServiceName() {
    return "";
  }

  @Override
  public CharSequence getResourceName() {
    return null;
  }

  @Override
  public Integer getSamplingPriority() {
    return this.spanContext.getSamplingPriority();
  }

  @Override
  public String getSpanType() {
    return null;
  }

  @Override
  public boolean isOutbound() {
    return false;
  }

  @Override
  public Object getTag(final String tag) {
    if (this.spanContext instanceof TagContext) {
      return ((TagContext) this.spanContext).getTags().getObject(tag);
    }
    return null;
  }

  @Override
  public TagMap getTags() {
    if (this.spanContext instanceof TagContext) {
      return ((TagContext) this.spanContext).getTags();
    } else {
      return TagMap.EMPTY;
    }
  }

  @Override
  public String getBaggageItem(final String key) {
    Iterable<Map.Entry<String, String>> baggage = this.spanContext.baggageItems();
    for (Map.Entry<String, String> stringStringEntry : baggage) {
      if (stringStringEntry.getKey().equals(key)) {
        return stringStringEntry.getValue();
      }
    }
    return null;
  }

  @Override
  public AgentSpanContext context() {
    return this.spanContext;
  }

  @Override
  public TraceConfig traceConfig() {
    return null;
  }

  @Override
  public boolean isSameTrace(AgentSpan otherSpan) {
    return null != otherSpan && getTraceId().equals(otherSpan.getTraceId());
  }

  @Override
  public RequestContext getRequestContext() {
    return RequestContext.Noop.INSTANCE;
  }

  @Override
  public RequestBlockingAction getRequestBlockingAction() {
    return null;
  }

  @Override
  public String toString() {
    return "ExtractedSpan{spanContext=" + this.spanContext + '}';
  }
}

package com.datadog.profiling.context;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.Collections;
import java.util.Map;

public final class TestSpan implements AgentSpan {

  private final boolean eligibleForDropping;

  public TestSpan() {
    this(false);
  }

  public TestSpan(boolean eligibleForDropping) {
    this.eligibleForDropping = eligibleForDropping;
  }

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public AgentSpan setTag(final String key, final boolean value) {
    return this;
  }

  @Override
  public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {}

  @Override
  public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
    return null;
  }

  @Override
  public AgentSpan setTag(final String tag, final Number value) {
    return this;
  }

  @Override
  public boolean isError() {
    return false;
  }

  @Override
  public AgentSpan setTag(final String key, final int value) {
    return this;
  }

  @Override
  public AgentSpan setTag(final String key, final long value) {
    return this;
  }

  @Override
  public AgentSpan setTag(final String key, final double value) {
    return this;
  }

  @Override
  public AgentSpan setTag(final String key, final Object value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(final CharSequence key, final int value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(final CharSequence key, final long value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(final CharSequence key, final double value) {
    return this;
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
  public AgentSpan setOperationName(final CharSequence serviceName) {
    return this;
  }

  @Override
  public String getServiceName() {
    return null;
  }

  @Override
  public AgentSpan setServiceName(final String serviceName) {
    return this;
  }

  @Override
  public CharSequence getResourceName() {
    return null;
  }

  @Override
  public AgentSpan setResourceName(final CharSequence resourceName) {
    return this;
  }

  @Override
  public AgentSpan setResourceName(final CharSequence resourceName, byte priority) {
    return this;
  }

  @Override
  public boolean eligibleForDropping() {
    return eligibleForDropping;
  }

  @Override
  public void startWork() {}

  @Override
  public void finishWork() {}

  @Override
  public RequestContext getRequestContext() {
    return null;
  }

  @Override
  public void mergePathwayContext(PathwayContext pathwayContext) {}

  @Override
  public Integer getSamplingPriority() {
    return (int) PrioritySampling.UNSET;
  }

  @Override
  public AgentSpan setSamplingPriority(final int newPriority) {
    return this;
  }

  @Override
  public String getSpanType() {
    return null;
  }

  @Override
  public AgentSpan setSpanType(final CharSequence type) {
    return this;
  }

  @Override
  public Map<String, Object> getTags() {
    return Collections.emptyMap();
  }

  @Override
  public AgentSpan setTag(final String key, final String value) {
    return this;
  }

  @Override
  public AgentSpan setTag(final String key, final CharSequence value) {
    return this;
  }

  @Override
  public AgentSpan setError(final boolean error) {
    return this;
  }

  @Override
  public AgentSpan setMeasured(boolean measured) {
    return this;
  }

  @Override
  public AgentSpan getRootSpan() {
    return this;
  }

  @Override
  public AgentSpan setErrorMessage(final String errorMessage) {
    return this;
  }

  @Override
  public AgentSpan addThrowable(final Throwable throwable) {
    return this;
  }

  @Override
  public AgentSpan setHttpStatusCode(int statusCode) {
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
    return otherSpan == this;
  }

  @Override
  public Context context() {
    return AgentTracer.NoopContext.INSTANCE;
  }

  @Override
  public String getBaggageItem(final String key) {
    return null;
  }

  @Override
  public AgentSpan setBaggageItem(final String key, final String value) {
    return this;
  }

  @Override
  public void finish() {}

  @Override
  public void finish(final long finishMicros) {}

  @Override
  public void finishWithDuration(final long durationNanos) {}

  @Override
  public void beginEndToEnd() {}

  @Override
  public void finishWithEndToEnd() {}

  @Override
  public boolean phasedFinish() {
    return false;
  }

  @Override
  public void publish() {}

  @Override
  public String getSpanName() {
    return "";
  }

  @Override
  public void setSpanName(final CharSequence spanName) {}

  @Override
  public boolean hasResourceName() {
    return false;
  }

  @Override
  public byte getResourceNamePriority() {
    return Byte.MAX_VALUE;
  }
}

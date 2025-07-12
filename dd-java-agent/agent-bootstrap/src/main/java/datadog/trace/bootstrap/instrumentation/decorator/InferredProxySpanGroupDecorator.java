package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import java.util.Map;

public class InferredProxySpanGroupDecorator implements AgentSpan {
  private final AgentSpan inferredProxySpan;
  private final AgentSpan serverSpan;

  InferredProxySpanGroupDecorator(AgentSpan inferredProxySpan, AgentSpan serverSpan) {
    this.inferredProxySpan = inferredProxySpan;
    this.serverSpan = serverSpan;
  }

  @Override
  public DDTraceId getTraceId() {
    return serverSpan.getTraceId();
  }

  @Override
  public long getSpanId() {
    return serverSpan.getSpanId();
  }

  @Override
  public AgentSpan setTag(String key, boolean value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, int value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, long value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, double value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, String value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, CharSequence value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setTag(String key, Object value) {
    return serverSpan.setTag(key, value);
  }

  /**
   * @param map
   * @return
   */
  @Override
  public AgentSpan setAllTags(Map<String, ?> map) {
    return null;
  }

  @Override
  public AgentSpan setTag(String key, Number value) {
    return serverSpan.setTag(key, value);
  }

  @Override
  public AgentSpan setMetric(CharSequence key, int value) {
    return serverSpan.setMetric(key, value);
  }

  @Override
  public AgentSpan setMetric(CharSequence key, long value) {
    return serverSpan.setMetric(key, value);
  }

  @Override
  public AgentSpan setMetric(CharSequence key, double value) {
    return serverSpan.setMetric(key, value);
  }

  @Override
  public AgentSpan setSpanType(CharSequence type) {
    return serverSpan.setSpanType(type);
  }

  @Override
  public Object getTag(String key) {
    return serverSpan.getTag(key);
  }

  @Override
  public AgentSpan setError(boolean error) {
    serverSpan.setError(error);
    if (inferredProxySpan != null) {
      inferredProxySpan.setError(error);
    }
    return this;
  }

  @Override
  public AgentSpan setError(boolean error, byte priority) {
    serverSpan.setError(error, priority);
    if (inferredProxySpan != null) {
      inferredProxySpan.setError(error, priority);
    }
    return this;
  }

  @Override
  public AgentSpan setMeasured(boolean measured) {
    return serverSpan.setMeasured(measured);
  }

  @Override
  public AgentSpan setErrorMessage(String errorMessage) {
    return serverSpan.setErrorMessage(errorMessage);
  }

  @Override
  public AgentSpan addThrowable(Throwable throwable) {
    serverSpan.addThrowable(throwable);
    if (inferredProxySpan != null) {
      inferredProxySpan.addThrowable(throwable);
    }
    return this;
  }

  @Override
  public AgentSpan addThrowable(Throwable throwable, byte errorPriority) {
    serverSpan.addThrowable(throwable, errorPriority);
    if (inferredProxySpan != null) {
      inferredProxySpan.addThrowable(throwable, errorPriority);
    }
    return this;
  }

  @Override
  public AgentSpan getLocalRootSpan() {
    return serverSpan.getLocalRootSpan();
  }

  @Override
  public boolean isSameTrace(AgentSpan otherSpan) {
    return serverSpan.isSameTrace(otherSpan);
  }

  @Override
  public AgentSpanContext context() {
    return serverSpan.context();
  }

  @Override
  public String getBaggageItem(String key) {
    return serverSpan.getBaggageItem(key);
  }

  @Override
  public AgentSpan setBaggageItem(String key, String value) {
    return serverSpan.setBaggageItem(key, value);
  }

  @Override
  public AgentSpan setHttpStatusCode(int statusCode) {
    serverSpan.setHttpStatusCode(statusCode);
    if (inferredProxySpan != null) {
      inferredProxySpan.setHttpStatusCode(statusCode);
    }
    return this;
  }

  @Override
  public short getHttpStatusCode() {
    return serverSpan.getHttpStatusCode();
  }

  @Override
  public void finish() {
    serverSpan.finish();
    if (inferredProxySpan != null) {
      inferredProxySpan.finish();
    }
  }

  @Override
  public void finish(long finishMicros) {
    serverSpan.finish(finishMicros);
    if (inferredProxySpan != null) {
      inferredProxySpan.finish(finishMicros);
    }
  }

  @Override
  public void finishWithDuration(long durationNanos) {
    serverSpan.finishWithDuration(durationNanos);
    if (inferredProxySpan != null) {
      inferredProxySpan.finishWithDuration(durationNanos);
    }
  }

  @Override
  public void beginEndToEnd() {
    serverSpan.beginEndToEnd();
  }

  @Override
  public void finishWithEndToEnd() {
    serverSpan.finishWithEndToEnd();
    if (inferredProxySpan != null) {
      inferredProxySpan.finishWithEndToEnd();
    }
  }

  @Override
  public boolean phasedFinish() {
    final boolean ret = serverSpan.phasedFinish();
    if (inferredProxySpan != null) {
      inferredProxySpan.phasedFinish();
    }
    return ret;
  }

  @Override
  public void publish() {
    serverSpan.publish();
  }

  @Override
  public CharSequence getSpanName() {
    return serverSpan.getSpanName();
  }

  @Override
  public void setSpanName(CharSequence spanName) {
    serverSpan.setSpanName(spanName);
  }

  @Deprecated
  @Override
  public boolean hasResourceName() {
    return serverSpan.hasResourceName();
  }

  @Override
  public byte getResourceNamePriority() {
    return serverSpan.getResourceNamePriority();
  }

  @Override
  public AgentSpan setResourceName(CharSequence resourceName) {
    return serverSpan.setResourceName(resourceName);
  }

  @Override
  public AgentSpan setResourceName(CharSequence resourceName, byte priority) {
    return serverSpan.setResourceName(resourceName, priority);
  }

  @Override
  public RequestContext getRequestContext() {
    return serverSpan.getRequestContext();
  }

  @Override
  public Integer forceSamplingDecision() {
    return serverSpan.forceSamplingDecision();
  }

  @Override
  public AgentSpan setSamplingPriority(int newPriority, int samplingMechanism) {
    return serverSpan.setSamplingPriority(newPriority, samplingMechanism);
  }

  @Override
  public TraceConfig traceConfig() {
    return serverSpan.traceConfig();
  }

  @Override
  public void addLink(AgentSpanLink link) {
    serverSpan.addLink(link);
  }

  @Override
  public AgentSpan setMetaStruct(String field, Object value) {
    return serverSpan.setMetaStruct(field, value);
  }

  @Override
  public boolean isOutbound() {
    return serverSpan.isOutbound();
  }

  @Override
  public AgentSpan asAgentSpan() {
    return serverSpan.asAgentSpan();
  }

  @Override
  public long getStartTime() {
    return serverSpan.getStartTime();
  }

  @Override
  public long getDurationNano() {
    return serverSpan.getDurationNano();
  }

  @Override
  public CharSequence getOperationName() {
    return serverSpan.getOperationName();
  }

  @Override
  public MutableSpan setOperationName(CharSequence serviceName) {
    return serverSpan.setOperationName(serviceName);
  }

  @Override
  public String getServiceName() {
    return serverSpan.getServiceName();
  }

  @Override
  public MutableSpan setServiceName(String serviceName) {
    return serverSpan.setServiceName(serviceName);
  }

  @Override
  public CharSequence getResourceName() {
    return serverSpan.getResourceName();
  }

  @Override
  public Integer getSamplingPriority() {
    return serverSpan.getSamplingPriority();
  }

  @Deprecated
  @Override
  public MutableSpan setSamplingPriority(int newPriority) {
    return serverSpan.setSamplingPriority(newPriority);
  }

  @Override
  public String getSpanType() {
    return serverSpan.getSpanType();
  }

  @Override
  public Map<String, Object> getTags() {
    return serverSpan.getTags();
  }

  @Override
  public boolean isError() {
    return serverSpan.isError();
  }

  @Deprecated
  @Override
  public MutableSpan getRootSpan() {
    return serverSpan.getRootSpan();
  }

  @Override
  public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {
    serverSpan.setRequestBlockingAction(rba);
  }

  @Override
  public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
    return serverSpan.getRequestBlockingAction();
  }
}

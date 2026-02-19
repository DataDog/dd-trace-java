package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.TagMap;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Map;

/**
 * An abstract implementation of an {@link AgentSpan} with disabled mutators.
 *
 * <p>As {@link AgentSpan} is a {@link MutableSpan} by design, this implementation offers an
 * alternative to provide read-only span implementations.
 */
public abstract class ImmutableSpan implements AgentSpan {
  @Override
  public AgentSpan setTag(String key, boolean value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, int value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, long value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, float value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, double value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, String value) {
    return this;
  }

  @Override
  public AgentSpan setTag(TagMap.EntryReader entry) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, CharSequence value) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, Object value) {
    return this;
  }

  @Override
  public AgentSpan setAllTags(Map<String, ?> map) {
    return this;
  }

  @Override
  public AgentSpan setTag(String key, Number value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(CharSequence key, int value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(CharSequence key, long value) {
    return this;
  }
  
  @Override
  public AgentSpan setMetric(CharSequence key, float value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(CharSequence key, double value) {
    return this;
  }

  @Override
  public AgentSpan setMetric(TagMap.EntryReader entry) {
    return this;
  }

  @Override
  public AgentSpan setSpanType(CharSequence type) {
    return this;
  }

  @Override
  public AgentSpan setError(boolean error) {
    return this;
  }

  @Override
  public AgentSpan setError(boolean error, byte priority) {
    return this;
  }

  @Override
  public AgentSpan setMeasured(boolean measured) {
    return this;
  }

  @Override
  public AgentSpan setErrorMessage(String errorMessage) {
    return this;
  }

  @Override
  public AgentSpan addThrowable(Throwable throwable) {
    return this;
  }

  @Override
  public AgentSpan addThrowable(Throwable throwable, byte errorPriority) {
    return this;
  }

  @Override
  public AgentSpan setBaggageItem(String key, String value) {
    return null;
  }

  @Override
  public AgentSpan setHttpStatusCode(int statusCode) {
    return this;
  }

  @Override
  public void finish() {}

  @Override
  public void finish(long finishMicros) {}

  @Override
  public void finishWithDuration(long durationNanos) {}

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
  public void setSpanName(CharSequence spanName) {}

  @Override
  public MutableSpan setOperationName(CharSequence serviceName) {
    return this;
  }

  @Override
  public MutableSpan setServiceName(String serviceName) {
    return this;
  }

  @Override
  public AgentSpan setResourceName(CharSequence resourceName) {
    return this;
  }

  @Override
  public MutableSpan setSamplingPriority(int newPriority) {
    return null;
  }

  @Override
  public AgentSpan setResourceName(CharSequence resourceName, byte priority) {
    return null;
  }

  @Override
  public Integer forceSamplingDecision() {
    return null;
  }

  @Override
  public AgentSpan setSamplingPriority(int newPriority, int samplingMechanism) {
    return this;
  }

  @Override
  public void addLink(AgentSpanLink link) {}

  @Override
  public AgentSpan setMetaStruct(String field, Object value) {
    return this;
  }

  @Override
  public void setRequestBlockingAction(RequestBlockingAction rba) {}
}

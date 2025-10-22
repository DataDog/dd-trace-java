package datadog.trace.agent.test

import datadog.trace.api.DDTraceId
import datadog.trace.api.TagMap
import datadog.trace.api.TraceConfig
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.bootstrap.CallDepthThreadLocalMap
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink
import datadog.trace.core.DDSpan
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.util.concurrent.ConcurrentHashMap

/**
 * Decorator for {@link AgentSpan} that keeps track of the decorated span's finish location.
 * The locations are stored in {@link InstrumentationSpecification#spanFinishLocations} and
 * are used to verify that the decorated span is not finished twice (see {@link InstrumentationSpecification#doCheckRepeatedFinish}).
 *
 * <p>It also wraps the span's {@link RequestContext} with {@link ValidatingRequestContextDecorator}
 */
@SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
class TrackingSpanDecorator implements AgentSpan {

  private final AgentSpan delegate
  private final ConcurrentHashMap<AgentSpan, List<Exception>> spanFinishLocations
  private final ConcurrentHashMap<AgentSpan, AgentSpan> originalToTrackingSpan
  private final RequestContext spiedRequestContext

  TrackingSpanDecorator(AgentSpan delegate,
  ConcurrentHashMap<AgentSpan, List<Exception>> spanFinishLocations,
  ConcurrentHashMap<AgentSpan, AgentSpan> originalToTrackingSpan,
  boolean useStrictTraceWrites) {
    this.delegate = delegate
    this.spanFinishLocations = spanFinishLocations
    this.originalToTrackingSpan = originalToTrackingSpan

    RequestContext requestContext = delegate.getRequestContext()
    this.spiedRequestContext = new ValidatingRequestContextDecorator(requestContext, this, useStrictTraceWrites)
  }

  @Override
  void finish() {
    handleFinish {
      it.finish()
    }
  }

  @Override
  void finish(long finishMicros) {
    handleFinish {
      it.finish(finishMicros)
    }
  }

  @Override
  void finishWithDuration(long durationNanos) {
    handleFinish {
      it.finishWithDuration(durationNanos)
    }
  }

  @Override
  void finishWithEndToEnd() {
    handleFinish {
      it.finishWithEndToEnd()
    }
  }

  private void handleFinish(Closure<AgentSpan> delegateCall) {
    def depth = CallDepthThreadLocalMap.incrementCallDepth(DDSpan)
    try {
      if (depth > 0) {
        return
      }
      List<Exception> locations
      List<Exception> newLocations
      do {
        locations = spanFinishLocations.get(delegate)
        newLocations = (locations ?: []) + new Exception()
      } while (!(locations == null ?
      spanFinishLocations.putIfAbsent(delegate, newLocations) == null :
      spanFinishLocations.replace(delegate, locations, newLocations)))
        delegateCall.call(delegate)
    } finally {
      CallDepthThreadLocalMap.decrementCallDepth(DDSpan)
    }
  }

  @Override
  AgentSpan getLocalRootSpan() {
    AgentSpan localRootSpan = delegate.getLocalRootSpan()
    return originalToTrackingSpan.getOrDefault(localRootSpan, localRootSpan)
  }

  @Override
  RequestContext getRequestContext() {
    return spiedRequestContext
  }

  @Override
  DDTraceId getTraceId() {
    return delegate.getTraceId()
  }

  @Override
  long getSpanId() {
    return delegate.getSpanId()
  }

  @Override
  AgentSpan setTag(String key, boolean value) {
    return delegate.setTag(key, value)
  }

  @Override
  void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {
    delegate.setRequestBlockingAction(rba)
  }

  @Override
  Flow.Action.RequestBlockingAction getRequestBlockingAction() {
    return delegate.getRequestBlockingAction()
  }

  @Override
  AgentSpan setTag(String key, int value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setTag(String key, long value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setTag(String key, double value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setTag(String key, String value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setTag(String key, CharSequence value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setTag(String key, Object value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setAllTags(Map<String, ?> map) {
    return delegate.setAllTags(map)
  }

  @Override
  AgentSpan setTag(String key, Number value) {
    return delegate.setTag(key, value)
  }

  @Override
  AgentSpan setMetric(CharSequence key, int value) {
    return delegate.setMetric(key, value)
  }

  @Override
  AgentSpan setMetric(CharSequence key, long value) {
    return delegate.setMetric(key, value)
  }

  @Override
  AgentSpan setMetric(CharSequence key, double value) {
    return delegate.setMetric(key, value)
  }

  @Override
  boolean isError() {
    return delegate.isError()
  }

  @Override
  AgentSpan setSpanType(CharSequence type) {
    return delegate.setSpanType(type)
  }

  @Override
  TagMap getTags() {
    return delegate.getTags()
  }

  @Override
  Object getTag(String key) {
    return delegate.getTag(key)
  }

  @Override
  AgentSpan setError(boolean error) {
    return delegate.setError(error)
  }

  @Override
  MutableSpan getRootSpan() {
    return delegate.getRootSpan()
  }

  @Override
  AgentSpan setError(boolean error, byte priority) {
    return delegate.setError(error, priority)
  }

  @Override
  AgentSpan setMeasured(boolean measured) {
    return delegate.setMeasured(measured)
  }

  @Override
  AgentSpan setErrorMessage(String errorMessage) {
    return delegate.setErrorMessage(errorMessage)
  }

  @Override
  AgentSpan addThrowable(Throwable throwable) {
    return delegate.addThrowable(throwable)
  }

  @Override
  AgentSpan addThrowable(Throwable throwable, byte errorPriority) {
    return delegate.addThrowable(throwable, errorPriority)
  }

  @Override
  boolean isSameTrace(AgentSpan otherSpan) {
    return delegate.isSameTrace(otherSpan)
  }

  @Override
  AgentSpanContext context() {
    return delegate.context()
  }

  @Override
  String getBaggageItem(String key) {
    return delegate.getBaggageItem(key)
  }

  @Override
  AgentSpan setBaggageItem(String key, String value) {
    return delegate.setBaggageItem(key, value)
  }

  @Override
  AgentSpan setHttpStatusCode(int statusCode) {
    return delegate.setHttpStatusCode(statusCode)
  }

  @Override
  short getHttpStatusCode() {
    return delegate.getHttpStatusCode()
  }

  @Override
  void beginEndToEnd() {
    delegate.beginEndToEnd()
  }

  @Override
  boolean phasedFinish() {
    return delegate.phasedFinish()
  }

  @Override
  void publish() {
    delegate.publish()
  }

  @Override
  CharSequence getSpanName() {
    return delegate.getSpanName()
  }

  @Override
  void setSpanName(CharSequence spanName) {
    delegate.setSpanName(spanName)
  }

  @Override
  boolean hasResourceName() {
    return delegate.hasResourceName()
  }

  @Override
  byte getResourceNamePriority() {
    return delegate.getResourceNamePriority()
  }

  @Override
  long getStartTime() {
    return delegate.getStartTime()
  }

  @Override
  long getDurationNano() {
    return delegate.getDurationNano()
  }

  @Override
  CharSequence getOperationName() {
    return delegate.getOperationName()
  }

  @Override
  MutableSpan setOperationName(CharSequence serviceName) {
    return delegate.setOperationName(serviceName)
  }

  @Override
  String getServiceName() {
    return delegate.getServiceName()
  }

  @Override
  MutableSpan setServiceName(String serviceName) {
    return delegate.setServiceName(serviceName)
  }

  @Override
  CharSequence getResourceName() {
    return delegate.getResourceName()
  }

  @Override
  AgentSpan setResourceName(CharSequence resourceName) {
    return delegate.setResourceName(resourceName)
  }

  @Override
  Integer getSamplingPriority() {
    return delegate.getSamplingPriority()
  }

  @Override
  MutableSpan setSamplingPriority(int newPriority) {
    return delegate.setSamplingPriority(newPriority)
  }

  @Override
  String getSpanType() {
    return delegate.getSpanType()
  }

  @Override
  AgentSpan setResourceName(CharSequence resourceName, byte priority) {
    return delegate.setResourceName(resourceName, priority)
  }

  @Override
  Integer forceSamplingDecision() {
    return delegate.forceSamplingDecision()
  }

  @Override
  AgentSpan setSamplingPriority(int newPriority, int samplingMechanism) {
    return delegate.setSamplingPriority(newPriority, samplingMechanism)
  }

  @Override
  TraceConfig traceConfig() {
    return delegate.traceConfig()
  }

  @Override
  void addLink(AgentSpanLink link) {
    delegate.addLink(link)
  }

  @Override
  AgentSpan setMetaStruct(String field, Object value) {
    return delegate.setMetaStruct(field, value)
  }

  @Override
  boolean isOutbound() {
    return delegate.isOutbound()
  }
}

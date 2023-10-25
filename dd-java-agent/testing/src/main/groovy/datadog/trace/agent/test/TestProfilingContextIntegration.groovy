package datadog.trace.agent.test

import datadog.trace.api.profiling.ProfilingContextAttribute
import datadog.trace.api.profiling.ProfilingScope
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration

import java.util.concurrent.atomic.AtomicInteger

class TestProfilingContextIntegration implements ProfilingContextIntegration {
  final AtomicInteger attachments = new AtomicInteger()
  final AtomicInteger detachments = new AtomicInteger()
  @Override
  void onAttach() {
    attachments.incrementAndGet()
  }

  @Override
  void onDetach() {
    detachments.incrementAndGet()
  }

  @Override
  void setContext(ProfilerContext profilerContext) {
  }

  @Override
  void clearContext() {
  }

  @Override
  void setContext(long rootSpanId, long spanId) {
  }

  @Override
  boolean isQueuingTimeEnabled() {
    return true
  }

  @Override
  void recordQueueingTime(long duration) {
  }

  @Override
  ProfilingContextAttribute createContextAttribute(String attribute) {
    return ProfilingContextAttribute.NoOp.INSTANCE
  }

  @Override
  ProfilingScope newScope() {
    return ProfilingScope.NO_OP
  }
}

package datadog.trace.agent.test

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
  int[] createContextStorage(CharSequence operationName) {
    return new int[0]
  }

  @Override
  void updateOperationName(CharSequence operationName, int[] storage, boolean active) {
  }

  @Override
  void setContextValue(String attribute, String value) {
  }

  @Override
  void clearContextValue(String attribute) {
  }

  @Override
  void setContext(int offset, int value) {
  }

  @Override
  void clearContext(int offset) {
  }
}

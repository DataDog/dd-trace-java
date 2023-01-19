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
  void setContextValue(String attribute, String value) {
  }

  @Override
  void clearContextValue(String attribute) {
  }

  @Override
  <T extends Enum<T>> void setContextValue(T attribute, String value) {
  }

  @Override
  <T extends Enum<T>> void clearContextValue(T attribute) {
  }
}

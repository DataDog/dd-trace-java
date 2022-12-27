package datadog.trace.agent.test

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration

import java.util.concurrent.atomic.AtomicInteger

class TestProfilingContextIntegration implements ProfilingContextIntegration {
  final AtomicInteger attachments = new AtomicInteger()
  final AtomicInteger detachments = new AtomicInteger()
  @Override
  void onAttach(int tid) {
    attachments.incrementAndGet()
  }

  @Override
  void onDetach(int tid) {
    detachments.incrementAndGet()
  }

  @Override
  void setContext(int tid, long rootSpanId, long spanId) {
  }

  @Override
  int getNativeThreadId() {
    return -1
  }
}

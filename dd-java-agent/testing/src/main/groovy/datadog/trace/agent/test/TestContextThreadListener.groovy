package datadog.trace.agent.test

import datadog.trace.bootstrap.instrumentation.api.ContextThreadListener

import java.util.concurrent.atomic.AtomicInteger

class TestContextThreadListener implements ContextThreadListener {
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
}

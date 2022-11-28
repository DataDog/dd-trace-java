package com.datadog.iast

import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadContext
import com.datadog.iast.overhead.OverheadController
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class NoopOverheadController extends OverheadController {
  @Override
  boolean acquireRequest() {
    true
  }

  @Override
  void releaseRequest() {
  }

  @Override
  boolean hasQuota(Operation operation, AgentSpan span) {
    true
  }

  @Override
  boolean consumeQuota(Operation operation, AgentSpan span) {
    true
  }

  @Override
  OverheadContext getContext(AgentSpan span) {
    new OverheadContext() {
        final int availableQuota = Integer.MAX_VALUE

        @Override
        boolean consumeQuota(int delta) {
          true
        }

        @Override
        void reset() {}
      }
  }

  @Override
  int computeSamplingParameter(float pct) {
    1
  }

  @Override
  void reset() {
  }
}

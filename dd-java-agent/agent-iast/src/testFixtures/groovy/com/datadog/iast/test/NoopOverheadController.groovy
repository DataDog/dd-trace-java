package com.datadog.iast.test

import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileStatic

@CompileStatic
class NoopOverheadController implements OverheadController {
  @Override
  boolean acquireRequest() {
    true
  }

  @Override
  int releaseRequest() {
    Integer.MAX_VALUE
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
  void reset() {
  }
}

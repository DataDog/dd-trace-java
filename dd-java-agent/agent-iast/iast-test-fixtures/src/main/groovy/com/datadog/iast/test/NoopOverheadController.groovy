package com.datadog.iast.test

import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.Operation
import com.datadog.iast.overhead.OverheadController
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import groovy.transform.CompileStatic
import javax.annotation.Nullable

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
  boolean hasQuota(Operation operation, @Nullable AgentSpan span) {
    true
  }

  @Override
  boolean consumeQuota(Operation operation, @Nullable AgentSpan span) {
    true
  }

  @Override
  boolean consumeQuota(Operation operation, @Nullable AgentSpan span, @Nullable VulnerabilityType type) {
    true
  }

  @Override
  void reset() {
  }
}

package com.datadog.debugger.agent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.NOOP_TRACER;

import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;

public class DebuggerTracer implements DebuggerContext.Tracer {
  public static final String OPERATION_NAME = "dd.dynamic.span";

  @Override
  public DebuggerSpan createSpan(String resourceName, String[] tags) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    if (tracerAPI == null || tracerAPI == NOOP_TRACER) {
      return DebuggerSpan.NOOP_SPAN;
    }
    AgentSpan dynamicSpan =
        tracerAPI.buildSpan(OPERATION_NAME).withResourceName(resourceName).start();
    if (tags != null) {
      for (String tag : tags) {
        int idx = tag.indexOf(':');
        if (idx == -1) {
          continue;
        }
        dynamicSpan.setTag(tag.substring(0, idx), tag.substring(idx + 1));
      }
    }
    AgentScope scope = tracerAPI.activateSpan(dynamicSpan, ScopeSource.MANUAL);
    return new DebuggerSpanImpl(dynamicSpan, scope);
  }

  static class DebuggerSpanImpl implements DebuggerSpan {
    final AgentSpan underlyingSpan;
    final AgentScope currentScope;

    public DebuggerSpanImpl(AgentSpan underlyingSpan, AgentScope currentScope) {
      this.underlyingSpan = underlyingSpan;
      this.currentScope = currentScope;
    }

    @Override
    public void finish() {
      currentScope.close();
      underlyingSpan.finish();
    }

    @Override
    public void setError(Throwable t) {
      underlyingSpan.setError(true);
      underlyingSpan.addThrowable(t);
    }
  }
}

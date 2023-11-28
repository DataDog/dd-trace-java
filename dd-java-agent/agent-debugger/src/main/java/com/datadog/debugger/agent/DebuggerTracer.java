package com.datadog.debugger.agent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.NOOP_TRACER;

import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerSpan;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;

public class DebuggerTracer implements DebuggerContext.Tracer {
  public static final String OPERATION_NAME = "dd.dynamic.span";

  private final DebuggerContext.ProbeResolver probeResolver;
  private final ProbeStatusSink probeStatusSink;

  public DebuggerTracer(
      DebuggerContext.ProbeResolver probeResolver, ProbeStatusSink probeStatusSink) {
    this.probeResolver = probeResolver;
    this.probeStatusSink = probeStatusSink;
  }

  @Override
  public DebuggerSpan createSpan(String probeId, String resourceName, String[] tags) {
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
    ProbeImplementation probeImplementation = probeResolver.resolve(probeId, null);
    if (probeImplementation == null) {
      return DebuggerSpan.NOOP_SPAN;
    }
    return new DebuggerSpanImpl(
        dynamicSpan, scope, probeStatusSink, probeImplementation.getProbeId());
  }

  static class DebuggerSpanImpl implements DebuggerSpan {
    final AgentSpan underlyingSpan;
    final AgentScope currentScope;
    final ProbeStatusSink probeStatusSink;
    final ProbeId probeId;

    public DebuggerSpanImpl(
        AgentSpan underlyingSpan,
        AgentScope currentScope,
        ProbeStatusSink probeStatusSink,
        ProbeId probeId) {
      this.underlyingSpan = underlyingSpan;
      this.currentScope = currentScope;
      this.probeStatusSink = probeStatusSink;
      this.probeId = probeId;
    }

    @Override
    public void finish() {
      currentScope.close();
      underlyingSpan.finish();
      probeStatusSink.addEmitting(probeId);
    }

    @Override
    public void setError(Throwable t) {
      underlyingSpan.setError(true);
      underlyingSpan.addThrowable(t);
    }
  }
}

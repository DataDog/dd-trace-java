package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.debugger.DebuggerSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import org.junit.jupiter.api.Test;

class DebuggerTracerTest {

  @Test
  public void createSpan() {
    AgentTracer.forceRegister(CoreTracer.builder().build());
    DebuggerTracer debuggerTracer = new DebuggerTracer();
    DebuggerSpan span = debuggerTracer.createSpan("a-span", new String[] {"foo:bar"});
    AgentSpan underlyingSpan = ((DebuggerTracer.DebuggerSpanImpl) span).underlyingSpan;
    assertEquals("a-span", underlyingSpan.getSpanName());
    assertEquals(0, underlyingSpan.getDurationNano());
    assertEquals(
        "a-span", ((DebuggerTracer.DebuggerSpanImpl) span).currentScope.span().getSpanName());
    span.finish();
    assertNotEquals(0, underlyingSpan.getDurationNano());
  }
}

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
    assertEquals("dd.dynamic.span", underlyingSpan.getSpanName());
    assertEquals("a-span", underlyingSpan.getResourceName());
    assertEquals(0, underlyingSpan.getDurationNano());
    assertEquals(
        "dd.dynamic.span",
        ((DebuggerTracer.DebuggerSpanImpl) span).currentScope.span().getSpanName());
    assertEquals(
        "a-span", ((DebuggerTracer.DebuggerSpanImpl) span).currentScope.span().getResourceName());
    span.finish();
    assertNotEquals(0, underlyingSpan.getDurationNano());
  }

  @Test
  public void setError() {
    AgentTracer.forceRegister(CoreTracer.builder().build());
    DebuggerTracer debuggerTracer = new DebuggerTracer();
    DebuggerSpan span = debuggerTracer.createSpan("a-span", new String[] {"foo:bar"});
    span.setError(new IllegalArgumentException("oops"));
    AgentSpan underlyingSpan = ((DebuggerTracer.DebuggerSpanImpl) span).underlyingSpan;
    assertTrue(underlyingSpan.isError());
  }
}

package com.datadog.debugger;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

public class TriggerProbe02 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(Integer value) throws ReflectiveOperationException {
    AgentSpan span = newSpan("main");
    AgentScope scope = tracerAPI.activateManualSpan(span);

    fullTrace(value);

    span.finish();
    scope.close();

    return 0;
  }

  private static void fullTrace(int value) throws NoSuchMethodException {
    AgentSpan span = newSpan("entry");
    AgentScope scope = tracerAPI.activateManualSpan(span);
    entry(value);
    span.finish();
    scope.close();
  }

  private static AgentSpan newSpan(String name) {
    return tracerAPI.buildSpan("code origin tests", name).start();
  }

  public static void entry(int value) throws NoSuchMethodException {
    // just to fill out the method body
    boolean dummyCode = true;
    if (!dummyCode) {
      dummyCode = false;
    }
  }
}

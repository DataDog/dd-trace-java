package com.datadog.debugger;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

public class TriggerProbe01 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(String arg) throws ReflectiveOperationException {
    AgentSpan span = newSpan("main");
    AgentScope scope = tracerAPI.activateManualSpan(span);

    fullTrace();

    span.finish();
    scope.close();

    return 0;
  }

  private static void fullTrace() throws NoSuchMethodException {
    AgentSpan span = newSpan("entry");
    AgentScope scope = tracerAPI.activateManualSpan(span);
    entry();
    span.finish();
    scope.close();

    span = newSpan("exit");
    scope = tracerAPI.activateManualSpan(span);
    exit();
    span.finish();
    scope.close();
  }

  private static AgentSpan newSpan(String name) {
    return tracerAPI.buildSpan("code origin tests", name).start();
  }

  public static void entry() throws NoSuchMethodException {
    // just to fill out the method body
    boolean dummyCode = true;
    if (!dummyCode) {
      dummyCode = false;
    }
  }

  private static void exit() {
    int x = 47 / 3;
  }

}

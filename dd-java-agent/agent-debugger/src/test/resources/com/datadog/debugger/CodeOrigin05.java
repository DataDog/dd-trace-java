package com.datadog.debugger;

import datadog.trace.bootstrap.debugger.spanorigin.CodeOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.DDSpan;

public class CodeOrigin05 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(String arg) throws ReflectiveOperationException {
    AgentSpan span = newSpan("main");
    AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL);
    if (arg.equals("debug_1")) {
      ((DDSpan) span.getLocalRootSpan()).setTag("_dd.p.debug", "1");
    } else if (arg.equals("debug_0")) {
      ((DDSpan) span.getLocalRootSpan()).setTag("_dd.p.debug", "0");
    }

    fullTrace();

    span.finish();
    scope.close();

    return 0;
  }

  private static void fullTrace() throws NoSuchMethodException {
    AgentSpan span = newSpan("entry");
    AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL);
    entry();
    span.finish();
    scope.close();

    span = newSpan("exit");
    scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL);
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
    doubleEntry();
  }

  private static void exit() {
    int x = 47 / 3;
  }

  public static void doubleEntry() throws NoSuchMethodException {
    // just to fill out the method body
    boolean dummyCode = true;
    if (!dummyCode) {
      dummyCode = false;
    }
  }

}

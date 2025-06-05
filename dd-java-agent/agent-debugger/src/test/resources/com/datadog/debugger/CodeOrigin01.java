package com.datadog.debugger;

import com.datadog.debugger.origin.CodeOrigin;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.core.DDSpan;

import static datadog.trace.bootstrap.debugger.DebuggerContext.*;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;

public class CodeOrigin01 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(String arg) throws ReflectiveOperationException {
    AgentSpan span = newSpan("main");
    AgentScope scope = tracerAPI.activateManualSpan(span);
    marker();
    captureCodeOrigin(CodeOrigin01.class.getDeclaredMethod("main", String.class), true);
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

  @CodeOrigin
  public static void fullTrace() throws NoSuchMethodException {
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

  @CodeOrigin
  public static void entry() throws NoSuchMethodException {
    // just to fill out the method body
    boolean dummyCode = true;
    if (!dummyCode) {
      dummyCode = false;
    }
  }

  @CodeOrigin
  private static void exit() {
    int x = 47 / 3;  // code origin 2
  }

}

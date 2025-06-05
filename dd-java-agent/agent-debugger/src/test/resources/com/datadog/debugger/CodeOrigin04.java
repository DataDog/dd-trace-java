package com.datadog.debugger;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;

public class CodeOrigin04 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(int level) throws ReflectiveOperationException {
    doExit(level);

    return level;
  }

  private static void doExit(int level) {
    if (level > 0) {
      doExit(level - 1);
    } else {
      AgentSpan span;
      AgentScope scope;
      span = newSpan("exit");
      scope = tracerAPI.activateManualSpan(span);
      exit();
      span.finish();
      scope.close();
    }
  }

  private static AgentSpan newSpan(String name) {
    return tracerAPI.buildSpan("code origin tests", name).start();
  }

  private static void exit() {
    int x = 47 / 3; // code origin 2
  }

}

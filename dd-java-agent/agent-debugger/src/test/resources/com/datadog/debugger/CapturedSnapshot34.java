package com.datadog.debugger;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * Target class for coordinated sampling budget tests. Each call to {@link #main(String)} is one
 * request running under its own local root span.
 */
public class CapturedSnapshot34 {
  private int intField = 42;

  public static int main(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "request").start();
    try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
      CapturedSnapshot34 instance = new CapturedSnapshot34();
      switch (arg) {
        case "A":
          return instance.entryA();
        case "C":
          return instance.entryC();
        case "D":
          return instance.entryD();
        case "loop":
          return instance.entryLoop();
        default:
          throw new IllegalArgumentException(arg);
      }
    } finally {
      span.finish();
    }
  }

  private int entryA() {
    return processB() + 1;
  }

  private int entryC() {
    return processB() + 2;
  }

  private int entryD() {
    return processB() + 3;
  }

  private int entryLoop() {
    int result = 0;
    for (int i = 0; i < 1000; i++) {
      result += processB();
    }
    return result + processE();
  }

  private int processB() {
    return intField;
  }

  private int processE() {
    return intField;
  }
}

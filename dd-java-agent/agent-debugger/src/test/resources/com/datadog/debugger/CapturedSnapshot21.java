package com.datadog.debugger;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CapturedSnapshot21 {
  private int intField = 42;
  private final String strField = "hello";
  private final List<String> strList = Arrays.asList("foobar1", "foobar2", "foobar3");
  private final Map<String, String> map = new HashMap<>();
  {
    map.put("foo1", "bar1");
    map.put("foo2", "bar2");
    map.put("foo3", "bar3");
  }

  public static int main(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "rootProcess").start();
    try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
      if (arg.equals("sibling")) {
        return new CapturedSnapshot21().rootSiblingProcess(arg);
      }
      if (arg.equals("loop")) {
        return new CapturedSnapshot21().rootLoopProcess(arg);
      }
      return new CapturedSnapshot21().rootProcess(arg);
    } finally {
      span.finish();
    }
  }

  private int rootSiblingProcess(String arg) {
    int result = 0;
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    {
      AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process1").start();
      try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
        result += siblingProcess1(arg);
      } finally {
        span.finish();
      }
    }
    {
      AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process2").start();
      try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
        result += siblingProcess2(arg);
      } finally {
        span.finish();
      }
    }
    {
      AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process2").start();
      try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
        result += siblingProcess3(arg);
      } finally {
        span.finish();
      }
    }
    return result;
  }

  private int siblingProcess1(String arg) {
    return intField;
  }

  private int siblingProcess2(String arg) {
    return intField;
  }

  private int siblingProcess3(String arg) {
    return intField;
  }

  private int rootLoopProcess(String arg) {
    int result = 0;
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    for (int i = 0; i < 10; i++) {
      AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process3").start();
      try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
        result += process3(arg);
      } finally {
        span.finish();
      }
    }
    return result;
  }

  private int rootProcess(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process1").start();
    try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
      return process1(arg) + 1;
    } finally {
      span.finish();
    }
  }

  private int process1(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process2").start();
    try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
      return process2(arg) + 1; // beae1817-f3b0-4ea8-a74f-000000000001
    } finally {
      span.finish();
    }
  }

  private int process2(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("dynamic-instrumentation", "process3").start();
    try (ContextScope scope = tracerAPI.activateManualSpan(span)) {
      return process3(arg) + 1; // beae1817-f3b0-4ea8-a74f-000000000002
    } finally {
      span.finish();
    }
  }

  private int process3(String arg) {
    return intField; // beae1817-f3b0-4ea8-a74f-000000000003
  }
}

package com.datadog.debugger;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
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
    AgentSpan span = tracerAPI.buildSpan("rootProcess").start();
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      return new CapturedSnapshot21().rootProcess(arg);
    } finally {
      span.finish();
    }
  }

  private int rootProcess(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("process1").start();
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      return process1(arg) + 1;
    } finally {
      span.finish();
    }
  }

  private int process1(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("process2").start();
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      return process2(arg) + 1;
    } finally {
      span.finish();
    }
  }

  private int process2(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("process3").start();
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      return process3(arg) + 1;
    } finally {
      span.finish();
    }
  }

  private int process3(String arg) {
    return intField; // beae1817-f3b0-4ea8-a74f-000000000001
  }
}

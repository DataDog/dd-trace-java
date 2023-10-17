package com.datadog.debugger;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.CoreTracer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CapturedSnapshot28 {
  private String password;
  private final Map<String, String> strMap = new HashMap<>();
  {
    strMap.put("foo1", "bar1");
    strMap.put("foo2", "bar2");
    strMap.put("foo3", "bar3");
  }

  public static int main(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("process").start();
    try (AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL)) {
      return new CapturedSnapshot28().process(arg);
    } finally {
      span.finish();
    }
  }

  private int process(String arg) {
    password = arg;
    String secret = arg;
    strMap.put("password", arg);
    return 42;
  }
}

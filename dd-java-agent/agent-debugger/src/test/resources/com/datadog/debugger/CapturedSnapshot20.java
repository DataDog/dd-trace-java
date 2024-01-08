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

public class CapturedSnapshot20 {
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
    AgentSpan span = tracerAPI.buildSpan("process").start();
    try (AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL)) {
      if (arg.equals("exception")) {
        return new CapturedSnapshot20().processWithException(arg);
      }
      return new CapturedSnapshot20().process(arg);
    } finally {
      span.finish();
    }
  }

  private int process(String arg) {
    int intLocal = intField + 42;
    return intLocal;
  }

  private int processWithException(String arg) {
    int intLocal = intField + 42;
    throw new RuntimeException("oops");
  }
}

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
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      if (arg.equals("exception") || arg.equals("illegal")) {
        return new CapturedSnapshot20().processWithException(arg);
      }
      if (arg.equals("recursive")) {
        return new CapturedSnapshot20().fiboException(10);
      }
      return new CapturedSnapshot20().process(arg);
    } catch (Exception ex) {
      span.addThrowable(ex);
      throw ex;
    } finally {
      span.finish();
    }
  }

  private int process(String arg) {
    int intLocal = intField + 42;
    return intLocal; // beae1817-f3b0-4ea8-a74f-000000000001
  }

  private int processWithException(String arg) {
    int intLocal = intField + 42;
    if (arg.equals("illegal")) {
      throw new IllegalArgumentException("illegal argument");
    }
    throw new RuntimeException("oops");
  }

  private int fiboException(int n) {
    if (n <= 1) {
      throw new RuntimeException("oops fibo");
    }
    try {
      return fiboException(n - 1) + fiboException(n - 2);
    } catch (Exception ex) {
      throw new RuntimeException(ex.getMessage(), ex);
    }
  }
}

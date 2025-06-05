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

public class CapturedSnapshot28 {
  private String password;
  private Creds creds;
  private final Map<String, String> strMap = new HashMap<>();
  private final Map<String, Creds> credMap = new HashMap<>();
  {
    strMap.put("foo1", "bar1");
    strMap.put("foo2", "bar2");
    strMap.put("foo3", "bar3");
    credMap.put("dave", new Creds("dave", "secret456"));
  }

  public static int main(String arg) {
    AgentTracer.TracerAPI tracerAPI = AgentTracer.get();
    AgentSpan span = tracerAPI.buildSpan("process").start();
    try (AgentScope scope = tracerAPI.activateManualSpan(span)) {
      return new CapturedSnapshot28().process(arg);
    } finally {
      span.finish();
    }
  }

  private int process(String arg) {
    creds = new Creds("john", arg);
    password = arg;
    String secret = arg;
    strMap.put("password", arg);
    return 42;
  }

  static class Creds {
    private String user;
    private String secretCode;

    public Creds(String user, String secretCode) {
      this.user = user;
      this.secretCode = secretCode;
    }
  }
}

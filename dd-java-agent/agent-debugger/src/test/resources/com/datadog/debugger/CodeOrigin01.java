package com.datadog.debugger;

import datadog.trace.bootstrap.debugger.spanorigin.CodeOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public class CodeOrigin01 {
  private int intField = 42;

  private static TracerAPI tracerAPI = AgentTracer.get();

  public static int main(String arg) throws ReflectiveOperationException {
    AgentSpan span = newSpan("main");
    try (AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL)) {
      if (arg.equals("fullTrace")) {
        return new CodeOrigin01().fullTrace();
      } else if (arg.equals("debug_1")) {
        span.getLocalRootSpan().setTag(Tags.PROPAGATED_DEBUG, "1");
        return new CodeOrigin01().fullTrace();
      } else if (arg.equals("debug_0")) {
        span.getLocalRootSpan().setTag(Tags.PROPAGATED_DEBUG, "0");
        return new CodeOrigin01().fullTrace();
      }
    } finally {
      span.finish();
    }

    return -1;
  }

  private int fullTrace() throws NoSuchMethodException {
    AgentSpan span = newSpan("entry");
    try(AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL)) {
      entry();
    } finally {
      span.finish();
    }
    exit();

    return 0;
  }

  private static AgentSpan newSpan(String name) {
    return tracerAPI.buildSpan("code origin tests", name).start();
  }

  public void entry() throws NoSuchMethodException {
    CodeOriginInfo.entry(CodeOrigin01.class.getMethod("entry"));
    // just to fill out the method body
    boolean dummyCode = true;
    if (!dummyCode) {
      dummyCode = false;
    }
  }

  private int exit() {
    AgentSpan span = newSpan("exit");
    try(AgentScope scope = tracerAPI.activateSpan(span, ScopeSource.MANUAL)) {
      CodeOriginInfo.exit(span);
      return 42;
    } finally {
      span.finish();
    }
  }

}

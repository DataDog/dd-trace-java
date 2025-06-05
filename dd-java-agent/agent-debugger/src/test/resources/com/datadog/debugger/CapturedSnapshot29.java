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
import java.util.Objects;

public class CapturedSnapshot29 {
  public static int main(String arg) {
    MyRecord1 myRecord1 = new MyRecord1("john", "doe", 42);
    MyRecord2 myRecord2 = new MyRecord2("john", "doe", 42);
    return myRecord1.age();
  }
}

record MyRecord1(String firstName, String lastName, int age) {}

record MyRecord2(String firstName, String lastName, int age) {

  MyRecord2 {
    Objects.requireNonNull(firstName); // beae1817-f3b0-4ea8-a74f-000000000001
    Objects.requireNonNull(lastName);
    if (age < 0) {
      throw new IllegalArgumentException("age < 0");
    }
  }
}

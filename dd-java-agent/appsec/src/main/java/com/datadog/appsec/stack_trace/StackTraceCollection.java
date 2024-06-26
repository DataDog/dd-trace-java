package com.datadog.appsec.stack_trace;

import java.util.Collection;

public class StackTraceCollection {
  public final Collection<StackTraceEvent> exploit;
  // TODO: Add vulnerability and exception collections for future use in IAST and APM

  public StackTraceCollection(Collection<StackTraceEvent> exploit) {
    this.exploit = exploit;
  }
}

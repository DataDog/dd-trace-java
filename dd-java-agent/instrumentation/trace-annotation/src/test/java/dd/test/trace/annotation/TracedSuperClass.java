package dd.test.trace.annotation;

import datadog.trace.api.Trace;

public abstract class TracedSuperClass {
  @Trace
  public abstract String testTracedAbstractMethod();

  @Trace
  public String testTracedSuperMethod() {
    return "Hello from super method";
  }

  @Trace
  public String testOverriddenTracedSuperMethod() {
    return "Expected to be overridden!";
  }
}

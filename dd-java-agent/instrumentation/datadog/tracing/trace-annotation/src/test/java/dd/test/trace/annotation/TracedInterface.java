package dd.test.trace.annotation;

import datadog.trace.api.Trace;

public interface TracedInterface {
  @Trace
  String testTracedInterfaceMethod();

  @Trace
  default String testTracedDefaultMethod() {
    return "Hello from default method";
  }

  @Trace
  default String testOverriddenTracedDefaultMethod() {
    return "Expected to be overridden!";
  }
}

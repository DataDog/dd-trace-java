package test.published.dependencies;

import datadog.trace.api.Trace;

public class App {
  public static void main(String[] args) {
    new App().tracedMethod();
  }

  @Trace
  void tracedMethod() {}
}

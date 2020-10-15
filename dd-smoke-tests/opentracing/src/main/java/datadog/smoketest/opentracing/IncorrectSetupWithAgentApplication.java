package datadog.smoketest.opentracing;

import datadog.opentracing.DDTracer;

public class IncorrectSetupWithAgentApplication {
  public static void main(final String[] args) {
    try {
      DDTracer.builder().build();
    } catch (IllegalStateException e) {
      if (e.getMessage().startsWith("Datadog Tracer already installed")) {
        return;
      }
    }

    throw new RuntimeException("build() did not throw exception");
  }
}

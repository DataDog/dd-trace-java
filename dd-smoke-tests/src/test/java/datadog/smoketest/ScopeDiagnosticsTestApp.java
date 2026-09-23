package datadog.smoketest;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Small child application that uses the actual packaged agent, not a second test tracer. */
public final class ScopeDiagnosticsTestApp {
  private ScopeDiagnosticsTestApp() {}

  public static void main(String[] args) throws Exception {
    if ("server".equals(args[0])) {
      BufferedReader input =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String action;
      while ((action = input.readLine()) != null && !"exit".equals(action)) {
        exercise(action);
        System.out.println("DONE");
        System.out.flush();
      }
    } else {
      exercise(args[0]);
    }
  }

  private static void exercise(String action) {
    AgentSpan span = AgentTracer.startSpan("smoke-diagnostic-test", "diagnostic-" + action);
    ContextContinuation continuation = AgentTracer.get().capture(span);
    if (!"leak".equals(action)) {
      ContextScope scope = continuation.resume();
      scope.close();
    }
    span.finish();
  }
}

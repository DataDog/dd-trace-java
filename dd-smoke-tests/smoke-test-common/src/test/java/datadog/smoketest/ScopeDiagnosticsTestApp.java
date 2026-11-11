package datadog.smoketest;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Small child application that uses the actual packaged agent, not a second test tracer. */
public final class ScopeDiagnosticsTestApp {
  private static ContextContinuation retained;

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

  private static void exercise(String action) throws Exception {
    AgentSpan span = AgentTracer.startSpan("smoke-diagnostic-test", "diagnostic-" + action);
    ContextContinuation continuation = AgentTracer.get().capture(span);
    if ("retain-released".equals(action)) {
      retained = continuation;
      retained.release();
    } else if ("retain-held".equals(action)) {
      retained = continuation.hold();
    } else if ("double".equals(action)) {
      continuation.release();
      continuation.release();
    } else if ("delayed".equals(action)) {
      new Thread(
              () -> {
                try {
                  Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  continuation.release();
                }
              },
              "delayed-continuation-cleanup")
          .start();
    } else if (!action.startsWith("leak")) {
      ContextScope scope = continuation.resume();
      scope.close();
    }
    if ("unclosed".equals(action) || "leak-and-unclosed".equals(action)) {
      AgentTracer.activateSpan(span);
    }
    span.finish();
    if (action.startsWith("resume-retained")) {
      FutureTask<Void> resume =
          new FutureTask<>(() -> {
            ContextScope scope = retained.resume();
            if (!"resume-retained-unclosed".equals(action)) {
              scope.close();
            }
            return null;
          });
      new Thread(resume, "retained-continuation-worker").start();
      resume.get(10, TimeUnit.SECONDS);
    } else if ("release-retained".equals(action)) {
      retained.release();
    }
  }
}

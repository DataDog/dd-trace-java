package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class DDContext {

  public static Supplier<CompletionStage<?>> wrap(
      Supplier<CompletionStage<?>> delegate, DDContext ddContext) {
    return () -> {
      try { // TODO would be better to handle scope creation here?
        return delegate.get();
      } finally {
        ddContext.closeScope();
      }
    };
  }

  private AgentSpan span;
  private AgentScope scope;

  public void openScope() {
    span = AgentTracer.startSpan("resilience4j", "resilience4j");
    scope = AgentTracer.activateSpan(span);

    //    AgentSpan parent = AgentTracer.activeSpan();
    //    AgentSpanContext parentContext =
    //        parent != null ? parent.context() : AgentTracer.noopSpanContext();

    //    if (parent == null || !parent.getSpanName().equals("resilience4j")) {
    //      span = AgentTracer.startSpan("resilience4j", "resilience4j", parentContext);
  }

  public void closeScope() {
    System.err.println(">> ddCloseScope " + Thread.currentThread().getName());
    if (scope != null) {
      scope.close();
      scope = null;
    }
  }

  public void finishSpan(Throwable error) {
    if (span != null) {
      // TODO set error tag
      span.finish();
      span = null;
    }
  }
}

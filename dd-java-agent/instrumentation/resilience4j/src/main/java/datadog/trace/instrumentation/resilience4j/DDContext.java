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
      ddContext.openScope();
      try { // TODO would be better to handle scope creation here? Yes, this also needed for retry
        CompletionStage<?> completionStage = delegate.get();
        completionStage.whenComplete(
            (result, error) -> {
              if (!(error instanceof Exception)) {
                // make sure we finish the span even if the completionStage has failed with
                // unhandled exception.
                // TODO write a test for this. See
                // io.github.resilience4j.retry.Retry.AsyncRetryBlock.run :: (throwable instanceof
                // Exception)
                ddContext.finishSpan(error);
              }
            });
        return completionStage;
      } finally {
        ddContext.closeScope();
      }
    };
  }

  private AgentSpan span;
  private AgentScope scope;

  public void openScope() {
    if (span == null) {
      span = AgentTracer.startSpan("resilience4j", "resilience4j");
    }
    if (scope == null) {
      scope = AgentTracer.activateSpan(span);
    }

    //    AgentSpan parent = AgentTracer.activeSpan();
    //    AgentSpanContext parentContext =
    //        parent != null ? parent.context() : AgentTracer.noopSpanContext();

    //    if (parent == null || !parent.getSpanName().equals("resilience4j")) {
    //      span = AgentTracer.startSpan("resilience4j", "resilience4j", parentContext);
  }

  public void closeScope() {
    if (scope == null) {
      return; // no scope to close
    }
    System.err.println(">> ddCloseScope " + Thread.currentThread().getName());
    scope.close();
    scope = null;
  }

  public void finishSpan(Throwable error) {
    if (span == null) {
      return;
    }
    // TODO set error tag
    span.finish();
    span = null;
  }
}

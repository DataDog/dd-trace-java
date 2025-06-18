package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class DDContext {
  public static final CharSequence CIRCUIT_BREAKER_SPAN =
      UTF8BytesString.create("resilience4j.circuit-breaker");
  public static final CharSequence RETRY_SPAN = UTF8BytesString.create("resilience4j.retry");
  private static final String INSTRUMENTATION_NAME = "resilience4j";

  private final CharSequence spanName;
  private AgentSpan span;
  private AgentScope scope;

  public static DDContext of(CircuitBreaker circuitBreaker) {
    return create(CIRCUIT_BREAKER_SPAN);
  }

  public static DDContext of(Retry retry) {
    return create(RETRY_SPAN);
  }

  private static DDContext create(CharSequence spanName) {
    // TODO right now create a separate span for each decorator layer
    return new DDContext(spanName);
  }

  private DDContext(CharSequence spanName) {
    this.spanName = spanName;
  }

  public CheckedSupplier<?> tracedCheckedSupplier(CheckedSupplier<?> delegate) {
    return () -> {
      openScope();
      try {
        return delegate.get();
      } finally {
        closeScope();
        finishSpan(null);
      }
    };
  }

  public Supplier<?> tracedSupplier(Supplier<?> delegate) {
    return () -> {
      openScope();
      try {
        return delegate.get();
      } finally {
        closeScope();
        finishSpan(null);
      }
    };
  }

  public Supplier<CompletionStage<?>> tracedCompletionStage(Supplier<CompletionStage<?>> delegate) {
    return () -> {
      // open a scope to be captured by the completionStage
      openScope();
      try {
        CompletionStage<?> completionStage = delegate.get();
        completionStage.whenComplete(
            (result, error) -> {
              System.err.println(">> whenComplete: " + error);
              finishSpan(error);
            });
        return completionStage;
      } finally {
        closeScope();
      }
    };
  }

  public void openScope() {
    if (span == null) {
      span = AgentTracer.startSpan(INSTRUMENTATION_NAME, spanName);
    }
    if (scope == null) {
      scope = AgentTracer.activateSpan(span);
      System.err.println(">> ddOpenScope " + Thread.currentThread().getName());
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

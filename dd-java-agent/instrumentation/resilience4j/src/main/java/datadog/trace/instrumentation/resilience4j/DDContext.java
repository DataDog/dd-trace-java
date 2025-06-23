package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

// TODO rename it to a utility method
public final class DDContext {
  public static final CharSequence CIRCUIT_BREAKER_SPAN =
      UTF8BytesString.create("resilience4j.circuit-breaker");
  public static final CharSequence RETRY_SPAN = UTF8BytesString.create("resilience4j.retry");
  public static final CharSequence FALLBACK_SPAN = UTF8BytesString.create("resilience4j.fallback");

  public static final CharSequence SPAN_NAME = UTF8BytesString.create("resilience4j");

  public static final String INSTRUMENTATION_NAME = "resilience4j";

  private AgentSpan span;
  private AgentScope scope;
  private boolean inherited;

  public static DDContext of(CircuitBreaker circuitBreaker) {
    return create(CIRCUIT_BREAKER_SPAN);
  }

  public static DDContext of(Retry retry) {
    return create(RETRY_SPAN);
  }

  public static DDContext ofFallback() {
    return create(FALLBACK_SPAN);
  }

  private static DDContext create(CharSequence spanName) {
    // TODO right now create a separate span for each decorator layer
    return new DDContext();
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
      openScope();
      CompletionStage<?> completionStage = delegate.get();
      closeScope();
      completionStage.whenComplete(
          (result, error) -> {
            if (error != null) {
              AgentSpan as = AgentTracer.activeSpan();
              as.setTag("error", error.getMessage());
            }
            System.err.println(">> whenComplete: " + error);
            finishSpan(error);
          });
      return completionStage;
    };
  }

  public void openScope() {
    if (span == null) {
      // TODO Do not rely on an active span. Instead, pass the decorator context at construction
      // time so that they have separate spans per decorator, even if they are nested.
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null && SPAN_NAME == activeSpan.getSpanName()) {
        span = activeSpan;
        inherited = false;
        return; // do not open scope
      } else {
        span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
        inherited = true;
      }
    }
    if (scope == null) {
      scope = AgentTracer.activateSpan(span);
      System.err.println(">> ddOpenScope " + Thread.currentThread().getName());
    }
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
    if (!inherited || span == null) {
      return;
    }
    // TODO set error tag
    span.finish();
    span = null;
  }
}
